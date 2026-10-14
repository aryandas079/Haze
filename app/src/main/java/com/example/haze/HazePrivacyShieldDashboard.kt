package com.example.haze

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.*
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.core.widget.NestedScrollView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog

object HazePrivacyShieldDashboard {

    private fun dpToPx(context: Context, dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            context.resources.displayMetrics
        ).toInt()
    }

    class ProtectionGraphView(
        context: Context,
        private val accentColor: Int,
        private val isDark: Boolean
    ) : View(context) {

        private var dataPoints = floatArrayOf(0.05f, 0.05f, 0.05f, 0.05f, 0.05f, 0.05f, 0.05f)
        private var dayLabels = arrayOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

        private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = accentColor
            style = Paint.Style.STROKE
            strokeWidth = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 3f, context.resources.displayMetrics)
            strokeCap = Paint.Cap.ROUND
        }

        private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }

        private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (isDark) Color.parseColor("#333333") else Color.parseColor("#E0E0E0")
            style = Paint.Style.STROKE
            strokeWidth = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1f, context.resources.displayMetrics)
            pathEffect = DashPathEffect(floatArrayOf(6f, 6f), 0f)
        }

        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (isDark) Color.parseColor("#9AA0A6") else Color.parseColor("#70757A")
            textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 11f, context.resources.displayMetrics)
            textAlign = Paint.Align.CENTER
        }

        fun updateData(points: FloatArray, labels: Array<String>) {
            this.dataPoints = points
            this.dayLabels = labels
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0 || h <= 0) return

            val horizontalPadding = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 24f, resources.displayMetrics)
            val topPadding = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 16f, resources.displayMetrics)
            val bottomPadding = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 32f, resources.displayMetrics)

            val graphLeft = horizontalPadding
            val graphRight = w - horizontalPadding
            val graphBottom = h - bottomPadding
            val usableWidth = graphRight - graphLeft
            val usableHeight = graphBottom - topPadding

            val numPoints = dataPoints.size
            if (numPoints < 2) return

            val stepX = usableWidth / (numPoints - 1)

            // 1. Vertical Grid Lines & Timeline Labels
            for (i in 0 until numPoints) {
                val x = graphLeft + i * stepX
                canvas.drawLine(x, topPadding, x, graphBottom, gridPaint)
                canvas.drawText(
                    dayLabels.getOrElse(i) { "" },
                    x,
                    graphBottom + TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 20f, resources.displayMetrics),
                    textPaint
                )
            }

            // 2. Smooth Bezier Curve Path
            val path = Path()
            val fillPath = Path()

            fun getY(index: Int): Float {
                val normY = dataPoints.getOrElse(index) { 0.0f }
                return graphBottom - (normY * usableHeight)
            }

            path.moveTo(graphLeft, getY(0))
            fillPath.moveTo(graphLeft, graphBottom)
            fillPath.lineTo(graphLeft, getY(0))

            for (i in 0 until numPoints - 1) {
                val x1 = graphLeft + i * stepX
                val y1 = getY(i)
                val x2 = graphLeft + (i + 1) * stepX
                val y2 = getY(i + 1)

                val cx1 = x1 + stepX / 2f
                val cy1 = y1
                val cx2 = x1 + stepX / 2f
                val cy2 = y2

                path.cubicTo(cx1, cy1, cx2, cy2, x2, y2)
                fillPath.cubicTo(cx1, cy1, cx2, cy2, x2, y2)
            }

            fillPath.lineTo(graphRight, graphBottom)
            fillPath.close()

            // 3. Fill Gradient
            val startAlpha = Color.argb(65, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor))
            val endAlpha = Color.argb(0, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor))
            fillPaint.shader = LinearGradient(0f, topPadding, 0f, graphBottom, startAlpha, endAlpha, Shader.TileMode.CLAMP)

            canvas.drawPath(fillPath, fillPaint)
            canvas.drawPath(path, linePaint)
        }
    }

    private fun computeGraphDataset(context: Context, category: String, range: String): Pair<FloatArray, Array<String>> {
        return HazeDatabase.getInstance(context).getProtectionHistory(range, category)
    }

    fun showDashboard(activity: MainActivity, initialScrollY: Int = 0, openInspect: Boolean = false, initialTopTab: Int = -1) {
        activity.triggerStrongHaptic(isHeavy = false)

        val prefs = activity.getSharedPreferences("HazePrefs", Context.MODE_PRIVATE)
        val isDark = prefs.getBoolean("is_dark", false) || activity.isPrivateMode

        val accentColorHex = prefs.getString("accent_color", "#0A56D0") ?: "#0A56D0"
        val accentColor = Color.parseColor(accentColorHex)
        val alphaAccentColor = Color.argb(35, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor))

        val bgColor = if (isDark) Color.parseColor("#1C1C1C") else Color.WHITE
        val cardColor = if (isDark) Color.parseColor("#252525") else Color.parseColor("#F6F8FA")
        val cardBorderColor = if (isDark) Color.parseColor("#333333") else Color.parseColor("#E8EAED")
        val textColor = if (isDark) Color.WHITE else Color.parseColor("#202124")
        val subTextColor = if (isDark) Color.parseColor("#9AA0A6") else Color.parseColor("#5F6368")
        val chipBgColor = if (isDark) Color.parseColor("#2E2E2E") else Color.parseColor("#EAEAEA")

        var selectedCategory = "All"
        var selectedRange = "7 days"

        val dialog = BottomSheetDialog(activity)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        dialog.setOnShowListener {
            val bottomSheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
                ?: dialog.window?.decorView?.findViewById(com.google.android.material.R.id.design_bottom_sheet)
            if (bottomSheet != null) {
                bottomSheet.setBackgroundColor(Color.TRANSPARENT)
                val behavior = BottomSheetBehavior.from(bottomSheet)
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
                val maxH = (activity.resources.displayMetrics.heightPixels * 0.92).toInt()
                bottomSheet.layoutParams = bottomSheet.layoutParams?.apply {
                    height = maxH
                } ?: ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, maxH)
                behavior.peekHeight = maxH
            }
        }

        val scrollView = NestedScrollView(activity).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            isVerticalScrollBarEnabled = false
            isFillViewport = true
            background = GradientDrawable().apply {
                cornerRadii = floatArrayOf(
                    dpToPx(activity, 28).toFloat(), dpToPx(activity, 28).toFloat(),
                    dpToPx(activity, 28).toFloat(), dpToPx(activity, 28).toFloat(),
                    0f, 0f, 0f, 0f
                )
                setColor(bgColor)
            }
        }

        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(activity, 20), dpToPx(activity, 8), dpToPx(activity, 20), dpToPx(activity, 28))
        }
        scrollView.addView(content)

        // --- Handle Pill ---
        val handleWrapper = FrameLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(activity, 18)).apply {
                bottomMargin = dpToPx(activity, 10)
            }
        }
        val handlePill = View(activity).apply {
            layoutParams = FrameLayout.LayoutParams(dpToPx(activity, 36), dpToPx(activity, 4)).apply {
                gravity = Gravity.CENTER
            }
            background = GradientDrawable().apply {
                cornerRadius = dpToPx(activity, 2).toFloat()
                setColor(if (isDark) Color.parseColor("#444444") else Color.parseColor("#D0D0D0"))
            }
        }
        handleWrapper.addView(handlePill)
        content.addView(handleWrapper)

        val activeTab = activity.getActiveTab()
        val currentUrl = activeTab?.url.orEmpty()
        val currentDomain = try {
            if (currentUrl.isNotBlank() && !currentUrl.startsWith("haze://") && !currentUrl.startsWith("about:") && !currentUrl.startsWith("data:")) {
                Uri.parse(currentUrl).host?.removePrefix("www.")?.lowercase()
            } else null
        } catch (_: Exception) { null }

        val isRealSite = !currentDomain.isNullOrBlank()

        val siteContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        val globalContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        // Top Segmented Switcher: [ This Website ]  [ Global Protection ]
        val tabSwitcher = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                cornerRadius = dpToPx(activity, 22).toFloat()
                setColor(if (isDark) Color.parseColor("#262626") else Color.parseColor("#ECEFF1"))
            }
            setPadding(dpToPx(activity, 4), dpToPx(activity, 4), dpToPx(activity, 4), dpToPx(activity, 4))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(activity, 44)).apply {
                bottomMargin = dpToPx(activity, 16)
            }
            visibility = if (isRealSite) View.VISIBLE else View.GONE
        }

        val btnTabSite = TextView(activity).apply {
            text = "This Website"
            textSize = 13.5f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
        }

        val btnTabGlobal = TextView(activity).apply {
            text = "Global Protection"
            textSize = 13.5f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
        }

        tabSwitcher.addView(btnTabSite)
        tabSwitcher.addView(btnTabGlobal)

        fun selectTopTab(index: Int) {
            if (index == 0) {
                btnTabSite.background = GradientDrawable().apply {
                    cornerRadius = dpToPx(activity, 18).toFloat()
                    setColor(if (isDark) Color.parseColor("#383838") else Color.WHITE)
                }
                btnTabSite.setTextColor(textColor)
                btnTabGlobal.background = null
                btnTabGlobal.setTextColor(subTextColor)
                siteContainer.visibility = View.VISIBLE
                globalContainer.visibility = View.GONE
            } else {
                btnTabGlobal.background = GradientDrawable().apply {
                    cornerRadius = dpToPx(activity, 18).toFloat()
                    setColor(if (isDark) Color.parseColor("#383838") else Color.WHITE)
                }
                btnTabGlobal.setTextColor(textColor)
                btnTabSite.background = null
                btnTabSite.setTextColor(subTextColor)
                siteContainer.visibility = View.GONE
                globalContainer.visibility = View.VISIBLE
            }
        }

        btnTabSite.setOnClickListener {
            activity.triggerStrongHaptic()
            selectTopTab(0)
        }
        btnTabGlobal.setOnClickListener {
            activity.triggerStrongHaptic()
            selectTopTab(1)
        }

        content.addView(tabSwitcher)
        content.addView(siteContainer)
        content.addView(globalContainer)

        var updateSiteTelemetry: (() -> Unit)? = null
        if (isRealSite) {
            val (siteView, updateFn) = buildSitePrivacyView(
                activity = activity,
                currentUrl = currentUrl,
                isDark = isDark,
                textColor = textColor,
                subTextColor = subTextColor,
                cardColor = cardColor,
                cardBorderColor = cardBorderColor,
                accentColor = accentColor,
                alphaAccentColor = alphaAccentColor,
                onSwitchToGlobal = { selectTopTab(1) }
            )
            siteContainer.addView(siteView)
            updateSiteTelemetry = updateFn
        }

        selectTopTab(if (initialTopTab in 0..1) initialTopTab else if (isRealSite) 0 else 1)

        // --- 1. Header Section ---
        val headerRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dpToPx(activity, 16)
            }
        }

        val iconBox = FrameLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(dpToPx(activity, 44), dpToPx(activity, 44)).apply {
                marginEnd = dpToPx(activity, 12)
            }
            background = GradientDrawable().apply {
                cornerRadius = dpToPx(activity, 14).toFloat()
                setColor(alphaAccentColor)
            }
        }
        val ivShield = ImageView(activity).apply {
            layoutParams = FrameLayout.LayoutParams(dpToPx(activity, 24), dpToPx(activity, 24)).apply {
                gravity = Gravity.CENTER
            }
            setImageResource(R.drawable.ic_privacy_shield)
            setColorFilter(accentColor)
        }
        iconBox.addView(ivShield)
        headerRow.addView(iconBox)

        val headerTextCol = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val tvTitle = TextView(activity).apply {
            text = "Privacy Shield"
            textSize = 19f
            setTypeface(null, Typeface.BOLD)
            setTextColor(textColor)
        }
        val tvSub = TextView(activity).apply {
            text = if (HazePrivacyShieldEngine.isShieldEnabled(activity)) "Shields UP • Active Protection" else "Shields Disabled"
            textSize = 12.5f
            setTextColor(if (HazePrivacyShieldEngine.isShieldEnabled(activity)) accentColor else subTextColor)
        }
        headerTextCol.addView(tvTitle)
        headerTextCol.addView(tvSub)
        headerRow.addView(headerTextCol)

        val masterSwitch = Switch(activity).apply {
            isChecked = HazePrivacyShieldEngine.isShieldEnabled(activity)
            val thumbColor = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(accentColor, if (isDark) Color.GRAY else Color.LTGRAY)
            )
            thumbTintList = thumbColor
        }
        headerRow.addView(masterSwitch)
        globalContainer.addView(headerRow)

        // --- 2. Blocked Today Hero Card (Global) ---
        val todayStats = HazePrivacyShieldEngine.getBlockedToday(activity)

        val heroCard = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dpToPx(activity, 20).toFloat()
                setColor(cardColor)
                setStroke(dpToPx(activity, 1), cardBorderColor)
            }
            setPadding(dpToPx(activity, 16), dpToPx(activity, 16), dpToPx(activity, 16), dpToPx(activity, 16))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dpToPx(activity, 16)
            }
        }

        // Card Title row: "Blocked today" + "● Live"
        val heroTitleRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dpToPx(activity, 12)
            }
        }
        val tvHeroTitle = TextView(activity).apply {
            text = "Blocked today"
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(textColor)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val tvHeroLive = TextView(activity).apply {
            text = "● Live"
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#10B981")) // Emerald Green
        }
        heroTitleRow.addView(tvHeroTitle)
        heroTitleRow.addView(tvHeroLive)
        heroCard.addView(heroTitleRow)

        // 4-Box Metric Grid: Ads, Trackers, Popups, Malware
        val row1 = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dpToPx(activity, 8)
            }
        }
        val row2 = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dpToPx(activity, 12)
            }
        }

        fun createMetricBox(label: String, initialValue: Long, badgeColor: Int): Pair<LinearLayout, TextView> {
            val box = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.START
                background = GradientDrawable().apply {
                    cornerRadius = dpToPx(activity, 14).toFloat()
                    setColor(if (isDark) Color.parseColor("#1F1F1F") else Color.WHITE)
                    setStroke(dpToPx(activity, 1), cardBorderColor)
                }
                setPadding(dpToPx(activity, 12), dpToPx(activity, 10), dpToPx(activity, 12), dpToPx(activity, 10))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = dpToPx(activity, 4)
                    marginStart = dpToPx(activity, 4)
                }
            }
            val tvLbl = TextView(activity).apply {
                text = label
                textSize = 12f
                setTextColor(subTextColor)
            }
            val tvVal = TextView(activity).apply {
                text = String.format(java.util.Locale.US, "%,d", initialValue)
                textSize = 21f
                setTypeface(null, Typeface.BOLD)
                setTextColor(badgeColor)
            }
            box.addView(tvLbl)
            box.addView(tvVal)
            return Pair(box, tvVal)
        }

        val (adsBox, tvAdsVal) = createMetricBox("Ads", todayStats.ads, accentColor)
        val (trackersBox, tvTrackersVal) = createMetricBox("Trackers", todayStats.trackers, Color.parseColor("#8B5CF6")) // Purple
        val (popupsBox, tvPopupsVal) = createMetricBox("Popups", todayStats.popups, Color.parseColor("#F59E0B")) // Amber
        val (malwareBox, tvMalwareVal) = createMetricBox("Malware", todayStats.malware, Color.parseColor("#EF4444")) // Crimson Red

        row1.addView(adsBox)
        row1.addView(trackersBox)
        row2.addView(popupsBox)
        row2.addView(malwareBox)

        heroCard.addView(row1)
        heroCard.addView(row2)

        val row3 = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dpToPx(activity, 12)
            }
        }
        val (tokensBox, tvTokensVal) = createMetricBox("Tokens Cleaned", todayStats.tokensCleaned, Color.parseColor("#3B82F6"))
        val (bouncesBox, tvBouncesVal) = createMetricBox("Bounces Stopped", todayStats.bouncesStopped, Color.parseColor("#10B981"))
        row3.addView(tokensBox)
        row3.addView(bouncesBox)
        heroCard.addView(row3)

        // Divider
        val divider = View(activity).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(activity, 1)).apply {
                bottomMargin = dpToPx(activity, 10)
            }
            setBackgroundColor(cardBorderColor)
        }
        heroCard.addView(divider)

        // Footer: Data Saved & Time Saved
        val footerRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val tvDataSaved = TextView(activity).apply {
            text = "💾 ${HazePrivacyShieldEngine.formatDataSaved(todayStats.dataSavedBytes)} Saved"
            textSize = 12.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(textColor)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val tvTimeSaved = TextView(activity).apply {
            text = "⏱️ ${HazePrivacyShieldEngine.formatTimeSaved(todayStats.timeSavedMs)}"
            textSize = 12.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(subTextColor)
        }

        footerRow.addView(tvDataSaved)
        footerRow.addView(tvTimeSaved)
        heroCard.addView(footerRow)
        globalContainer.addView(heroCard)

        // --- 4. Protection Timeline Graph & Dropdown Filters ---
        val filterRow = RelativeLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dpToPx(activity, 8)
            }
        }

        val chipBg = GradientDrawable().apply {
            cornerRadius = dpToPx(activity, 12).toFloat()
            setColor(chipBgColor)
        }

        val tvFilterCategory = TextView(activity).apply {
            text = "$selectedCategory  ▾"
            textSize = 13.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(textColor)
            background = chipBg
            setPadding(dpToPx(activity, 14), dpToPx(activity, 6), dpToPx(activity, 14), dpToPx(activity, 6))
            layoutParams = RelativeLayout.LayoutParams(RelativeLayout.LayoutParams.WRAP_CONTENT, RelativeLayout.LayoutParams.WRAP_CONTENT).apply {
                addRule(RelativeLayout.ALIGN_PARENT_START)
            }
        }
        val tvFilterDays = TextView(activity).apply {
            text = "$selectedRange  ▾"
            textSize = 13.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(textColor)
            background = GradientDrawable().apply {
                cornerRadius = dpToPx(activity, 12).toFloat()
                setColor(chipBgColor)
            }
            setPadding(dpToPx(activity, 14), dpToPx(activity, 6), dpToPx(activity, 14), dpToPx(activity, 6))
            layoutParams = RelativeLayout.LayoutParams(RelativeLayout.LayoutParams.WRAP_CONTENT, RelativeLayout.LayoutParams.WRAP_CONTENT).apply {
                addRule(RelativeLayout.ALIGN_PARENT_END)
            }
        }
        filterRow.addView(tvFilterCategory)
        filterRow.addView(tvFilterDays)
        globalContainer.addView(filterRow)

        val graphView = ProtectionGraphView(activity, accentColor, isDark).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(activity, 170)).apply {
                bottomMargin = dpToPx(activity, 16)
            }
        }
        globalContainer.addView(graphView)

        fun refreshGraph() {
            val (points, labels) = computeGraphDataset(activity, selectedCategory, selectedRange)
            graphView.updateData(points, labels)
        }
        refreshGraph()

        tvFilterCategory.setOnClickListener {
            activity.triggerStrongHaptic()
            val popup = PopupMenu(activity, tvFilterCategory)
            val categories = listOf("All", "Ads", "Trackers", "Popups", "Malware")
            categories.forEach { cat -> popup.menu.add(cat) }
            popup.setOnMenuItemClickListener { item ->
                selectedCategory = item.title.toString()
                tvFilterCategory.text = "$selectedCategory  ▾"
                refreshGraph()
                true
            }
            popup.show()
        }

        tvFilterDays.setOnClickListener {
            activity.triggerStrongHaptic()
            val popup = PopupMenu(activity, tvFilterDays)
            val ranges = listOf("24 hours", "7 days", "1 month", "1 year")
            ranges.forEach { r -> popup.menu.add(r) }
            popup.setOnMenuItemClickListener { item ->
                selectedRange = item.title.toString()
                tvFilterDays.text = "$selectedRange  ▾"
                refreshGraph()
                true
            }
            popup.show()
        }

        // --- 5. Segmented Navigation Tabs: [Protection] [Filter Lists] [My Rules] ---
        val tabPill = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                cornerRadius = dpToPx(activity, 14).toFloat()
                setColor(if (isDark) Color.parseColor("#282828") else Color.parseColor("#ECEFF1"))
            }
            setPadding(dpToPx(activity, 4), dpToPx(activity, 4), dpToPx(activity, 4), dpToPx(activity, 4))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(activity, 44)).apply {
                bottomMargin = dpToPx(activity, 16)
            }
        }

        val tabSelectedBg = GradientDrawable().apply {
            cornerRadius = dpToPx(activity, 10).toFloat()
            setColor(if (isDark) Color.parseColor("#383838") else Color.WHITE)
        }

        fun createTabBtn(label: String, isSelected: Boolean): TextView {
            return TextView(activity).apply {
                text = label
                textSize = 13f
                gravity = Gravity.CENTER
                setTypeface(null, if (isSelected) Typeface.BOLD else Typeface.NORMAL)
                setTextColor(if (isSelected) textColor else subTextColor)
                background = if (isSelected) tabSelectedBg else null
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            }
        }

        val btnTabProtection = createTabBtn("Features", true)
        val btnTabFilterLists = createTabBtn("Filter Lists", false)
        val btnTabRules = createTabBtn("My Rules", false)

        tabPill.addView(btnTabProtection)
        tabPill.addView(btnTabFilterLists)
        tabPill.addView(btnTabRules)
        globalContainer.addView(tabPill)

        // Tab Content 1: Protection Features Container
        val protectionContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.VISIBLE
            background = GradientDrawable().apply {
                cornerRadius = dpToPx(activity, 18).toFloat()
                setColor(cardColor)
                setStroke(dpToPx(activity, 1), cardBorderColor)
            }
            setPadding(dpToPx(activity, 16), dpToPx(activity, 8), dpToPx(activity, 16), dpToPx(activity, 8))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dpToPx(activity, 16)
            }
        }

        fun createFeatureToggleRow(title: String, desc: String, prefKey: String, defaultVal: Boolean = true, onChanged: ((Boolean) -> Unit)? = null): LinearLayout {
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dpToPx(activity, 10), 0, dpToPx(activity, 10))
            }
            val textCol = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val tv = TextView(activity).apply {
                text = title
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
                setTextColor(textColor)
            }
            val tvDesc = TextView(activity).apply {
                text = desc
                textSize = 11.5f
                setTextColor(subTextColor)
            }
            textCol.addView(tv)
            textCol.addView(tvDesc)
            row.addView(textCol)

            val sw = Switch(activity).apply {
                isChecked = prefs.getBoolean(prefKey, defaultVal)
                val thumbColor = ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(accentColor, if (isDark) Color.GRAY else Color.LTGRAY)
                )
                thumbTintList = thumbColor
                setOnCheckedChangeListener { _, checked ->
                    activity.triggerStrongHaptic()
                    prefs.edit().putBoolean(prefKey, checked).apply()
                    HazePrivacyShieldEngine.notifyListeners("CONFIG_CHANGE", HazePrivacyShieldEngine.getTotalBlocked(activity))
                    onChanged?.invoke(checked)
                }
            }
            row.addView(sw)
            return row
        }

        protectionContainer.addView(createFeatureToggleRow("Banner Ads & Networks", "Block display banners & advertising networks", HazePrivacyShieldEngine.PREF_BLOCK_ADS, true))
        protectionContainer.addView(createFeatureToggleRow("Popups & Popunders", "Block unwanted popup windows and redirects", HazePrivacyShieldEngine.PREF_BLOCK_POPUPS, true))
        protectionContainer.addView(createFeatureToggleRow("Video Ads & Auto-Mute", "Fast-forward video ads & auto-mute intrusive audio", HazePrivacyShieldEngine.PREF_BLOCK_VIDEO_ADS, true))
        protectionContainer.addView(createFeatureToggleRow("Sponsored Content Widgets", "Block Taboola, Outbrain, Revcontent & MGID", HazePrivacyShieldEngine.PREF_BLOCK_SPONSORED_WIDGETS, true))
        protectionContainer.addView(createFeatureToggleRow("Tracking Scripts & Telemetry", "Stop Google Analytics, Meta Pixel, TikTok & Clarity", HazePrivacyShieldEngine.PREF_BLOCK_TRACKERS, true))
        protectionContainer.addView(createFeatureToggleRow("Malvertising & Cryptominers", "Block Coinhive, malicious redirects & drive-by threats", HazePrivacyShieldEngine.PREF_BLOCK_MALWARE, true))
        protectionContainer.addView(createFeatureToggleRow("Annoying Overlays & Cookie Walls", "Dismiss cookie consent popups & unlock page scroll", HazePrivacyShieldEngine.PREF_BLOCK_OVERLAYS, true))
        protectionContainer.addView(createFeatureToggleRow("Third-Party Trackers", "Prevent cross-origin tracking beacons", HazePrivacyShieldEngine.PREF_BLOCK_THIRD_PARTY, true))
        protectionContainer.addView(createFeatureToggleRow("Third-Party Cookie Blocking", "Block cross-site tracking cookies across origins", HazeAntiTrackingEngine.PREF_THIRD_PARTY_COOKIES_BLOCKED, true) {
            activity.applyAntiTrackingCookiePolicy()
        })
        protectionContainer.addView(createFeatureToggleRow("First-Party Isolation", "Isolate storage & state per top-level website", HazeAntiTrackingEngine.PREF_FIRST_PARTY_ISOLATION, true))
        protectionContainer.addView(createFeatureToggleRow("Bounce Tracking Protection", "Unwrap & bypass intermediate redirect trampolines", HazeAntiTrackingEngine.PREF_BOUNCE_TRACKING_PROTECTION, true))
        protectionContainer.addView(createFeatureToggleRow("Link Tracking Protection", "Strip tracking tokens (utm_source, gclid, fbclid) from URLs", HazeAntiTrackingEngine.PREF_LINK_TRACKING_PROTECTION, true))
        protectionContainer.addView(createFeatureToggleRow("Canvas Protection", "Inject micro-noise to neutralize canvas hash tracking", HazeAntiTrackingEngine.PREF_CANVAS_PROTECTION, true))
        protectionContainer.addView(createFeatureToggleRow("WebGL Fingerprint Protection", "Mask GPU vendor & renderer hardware signatures", HazeAntiTrackingEngine.PREF_WEBGL_PROTECTION, true))
        protectionContainer.addView(createFeatureToggleRow("Audio Fingerprint Protection", "Prevent mathematical audio profile measurement", HazeAntiTrackingEngine.PREF_AUDIO_PROTECTION, true))
        protectionContainer.addView(createFeatureToggleRow("Font Fingerprint Protection", "Normalize font probe measurement sweeps", HazeAntiTrackingEngine.PREF_FONT_PROTECTION, true))
        protectionContainer.addView(createFeatureToggleRow("Device Fingerprint Protection", "Standardize CPU cores, RAM, and Battery API", HazeAntiTrackingEngine.PREF_DEVICE_PROTECTION, true))
        protectionContainer.addView(createFeatureToggleRow("WebRTC IP Leak Protection", "Sanitize ICE candidates to prevent real IP leaks", HazeAntiTrackingEngine.PREF_IP_LEAK_PROTECTION, true))
        protectionContainer.addView(createFeatureToggleRow("Referrer Protection", "Trim cross-origin referrers to domain origin only", HazeAntiTrackingEngine.PREF_REFERRER_PROTECTION, true))

        val dnsCard = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dpToPx(activity, 14).toFloat()
                setColor(cardColor)
                setStroke(dpToPx(activity, 1), cardBorderColor)
            }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dpToPx(activity, 10)
                bottomMargin = dpToPx(activity, 10)
            }
            setPadding(dpToPx(activity, 14), dpToPx(activity, 12), dpToPx(activity, 14), dpToPx(activity, 12))
            setOnClickListener {
                activity.triggerStrongHaptic()
                HazeDnsDialogManager.showDnsDialog(activity)
            }
        }
        val ivDnsIcon = ImageView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(dpToPx(activity, 32), dpToPx(activity, 32)).apply {
                marginEnd = dpToPx(activity, 12)
            }
            setImageResource(R.drawable.ic_dns)
            setColorFilter(accentColor)
        }
        val dnsTextCol = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val tvDnsTitle = TextView(activity).apply {
            text = "DNS Protection (DoH / DoT)"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(textColor)
        }
        val tvDnsSub = TextView(activity).apply {
            text = HazeDnsManager.getDnsSummary(activity)
            textSize = 11.5f
            setTextColor(subTextColor)
        }
        dnsTextCol.addView(tvDnsTitle)
        dnsTextCol.addView(tvDnsSub)
        dnsCard.addView(ivDnsIcon)
        dnsCard.addView(dnsTextCol)
        protectionContainer.addView(dnsCard)

        globalContainer.addView(protectionContainer)

        // Tab Content 2: Filter Lists Container
        val filterListsContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dpToPx(activity, 16)
            }
        }

        fun createFilterListRow(title: String, desc: String, badge: String, iconRes: Int, onClick: (() -> Unit)? = null): LinearLayout {
            val card = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = GradientDrawable().apply {
                    cornerRadius = dpToPx(activity, 16).toFloat()
                    setColor(cardColor)
                    setStroke(dpToPx(activity, 1), cardBorderColor)
                }
                setPadding(dpToPx(activity, 14), dpToPx(activity, 12), dpToPx(activity, 14), dpToPx(activity, 12))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dpToPx(activity, 10)
                }
                if (onClick != null) {
                    setOnClickListener {
                        activity.triggerStrongHaptic()
                        onClick()
                    }
                }
            }

            val iv = ImageView(activity).apply {
                layoutParams = LinearLayout.LayoutParams(dpToPx(activity, 22), dpToPx(activity, 22)).apply {
                    marginEnd = dpToPx(activity, 12)
                }
                setImageResource(iconRes)
                setColorFilter(accentColor)
            }
            card.addView(iv)

            val textCol = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val titleRow = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val tvT = TextView(activity).apply {
                text = title
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
                setTextColor(textColor)
            }
            val tvBadge = TextView(activity).apply {
                text = " $badge "
                textSize = 11f
                setTypeface(null, Typeface.BOLD)
                setTextColor(Color.parseColor("#10B981"))
                background = GradientDrawable().apply {
                    cornerRadius = dpToPx(activity, 6).toFloat()
                    setColor(if (isDark) Color.parseColor("#1B382B") else Color.parseColor("#E6F4EA"))
                }
                setPadding(dpToPx(activity, 6), dpToPx(activity, 2), dpToPx(activity, 6), dpToPx(activity, 2))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    marginStart = dpToPx(activity, 8)
                }
            }
            titleRow.addView(tvT)
            titleRow.addView(tvBadge)

            val tvD = TextView(activity).apply {
                text = desc
                textSize = 11.5f
                setTextColor(subTextColor)
            }
            textCol.addView(titleRow)
            textCol.addView(tvD)
            card.addView(textCol)

            if (onClick != null) {
                val ivArrow = ImageView(activity).apply {
                    layoutParams = LinearLayout.LayoutParams(dpToPx(activity, 18), dpToPx(activity, 18))
                    setImageResource(R.drawable.ic_arrow_forward)
                    setColorFilter(subTextColor)
                }
                card.addView(ivArrow)
            }
            return card
        }

        filterListsContainer.addView(createFilterListRow("EasyList", "Standard filter list blocking primary ads & banners", "Active • 48k rules", R.drawable.ic_privacy_shield))
        filterListsContainer.addView(createFilterListRow("EasyPrivacy", "Strict filter list neutralizing cross-site trackers", "Active • 34k rules", R.drawable.ic_private))

        // Regional Lists Card
        filterListsContainer.addView(createFilterListRow("Regional Lists", "6 regional filters available (DE, FR, ES, RU, IN, ZH)", "Configure", R.drawable.ic_globe) {
            showRegionalListsDialog(activity, isDark, accentColor, cardColor, cardBorderColor, textColor, subTextColor)
        })

        // Custom Subscriptions Card
        filterListsContainer.addView(createFilterListRow("Custom Lists", "Subscribe to any Adblock Plus or uBlock filter list URL", "Manage", R.drawable.ic_link) {
            showCustomSubscriptionsDialog(activity, isDark, accentColor, cardColor, cardBorderColor, textColor, subTextColor)
        })

        // Update Button
        val btnUpdateLists = TextView(activity).apply {
            text = "Check & Update Filter Lists Now"
            textSize = 13.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dpToPx(activity, 14), dpToPx(activity, 12), dpToPx(activity, 14), dpToPx(activity, 12))
            background = GradientDrawable().apply {
                cornerRadius = dpToPx(activity, 14).toFloat()
                setColor(accentColor)
            }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dpToPx(activity, 6)
                bottomMargin = dpToPx(activity, 10)
            }
            setOnClickListener {
                activity.triggerStrongHaptic()
                text = "Updating filters in background..."
                isEnabled = false
                HazeFilterListManager.updateRules(activity) { success, count, msg ->
                    activity.runOnUiThread {
                        isEnabled = true
                        text = "Check & Update Filter Lists Now"
                        Toast.makeText(activity, msg, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
        filterListsContainer.addView(btnUpdateLists)
        globalContainer.addView(filterListsContainer)

        // Tab Content 3: My Rules Container (User-Created Rules)
        val rulesContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dpToPx(activity, 16)
            }
        }

        // Syntax info card
        val syntaxCard = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dpToPx(activity, 16).toFloat()
                setColor(cardColor)
                setStroke(dpToPx(activity, 1), cardBorderColor)
            }
            setPadding(dpToPx(activity, 14), dpToPx(activity, 12), dpToPx(activity, 14), dpToPx(activity, 12))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dpToPx(activity, 12)
            }
        }
        val tvSyntaxTitle = TextView(activity).apply {
            text = "Supported Rule Syntax"
            textSize = 13.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(textColor)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dpToPx(activity, 4)
            }
        }
        val tvSyntaxBody = TextView(activity).apply {
            text = "• Network: ||domain.com^ (Block domain)\n• Cosmetic: ##.ad-banner or ##div[class*='sponsor']\n• Exception: @@||domain.com^ (Whitelist)"
            textSize = 12f
            setTextColor(subTextColor)
            setLineSpacing(dpToPx(activity, 2).toFloat(), 1f)
        }
        syntaxCard.addView(tvSyntaxTitle)
        syntaxCard.addView(tvSyntaxBody)
        rulesContainer.addView(syntaxCard)

        // "+ Add Custom Rule" Button
        val btnAddRule = TextView(activity).apply {
            text = "+ Add Custom Rule"
            textSize = 13.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(accentColor)
            gravity = Gravity.CENTER
            setPadding(dpToPx(activity, 14), dpToPx(activity, 12), dpToPx(activity, 14), dpToPx(activity, 12))
            background = GradientDrawable().apply {
                cornerRadius = dpToPx(activity, 14).toFloat()
                setColor(alphaAccentColor)
            }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dpToPx(activity, 12)
            }
        }
        rulesContainer.addView(btnAddRule)

        val rulesListWrapper = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        rulesContainer.addView(rulesListWrapper)

        fun populateUserRules() {
            rulesListWrapper.removeAllViews()
            val userRules = HazeDatabase.getInstance(activity).getUserCustomRules()
            if (userRules.isEmpty()) {
                val tvEmpty = TextView(activity).apply {
                    text = "No custom rules added yet.\nTap '+ Add Custom Rule' to create one."
                    textSize = 13f
                    gravity = Gravity.CENTER
                    setTextColor(subTextColor)
                    setPadding(0, dpToPx(activity, 20), 0, dpToPx(activity, 20))
                }
                rulesListWrapper.addView(tvEmpty)
                return
            }

            for (rule in userRules) {
                val ruleRow = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = GradientDrawable().apply {
                        cornerRadius = dpToPx(activity, 14).toFloat()
                        setColor(cardColor)
                        setStroke(dpToPx(activity, 1), cardBorderColor)
                    }
                    setPadding(dpToPx(activity, 12), dpToPx(activity, 10), dpToPx(activity, 12), dpToPx(activity, 10))
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                        bottomMargin = dpToPx(activity, 8)
                    }
                }

                val textCol = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }

                val tvRuleText = TextView(activity).apply {
                    text = rule.ruleText
                    textSize = 13f
                    setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
                    setTextColor(textColor)
                }

                val ruleTypeBadge = when {
                    rule.isException -> "Exception"
                    rule.isCosmetic -> "Cosmetic (Hide Element)"
                    else -> "Network (Block URL)"
                }
                val tvRuleBadge = TextView(activity).apply {
                    text = ruleTypeBadge
                    textSize = 11f
                    setTextColor(subTextColor)
                }
                textCol.addView(tvRuleText)
                textCol.addView(tvRuleBadge)
                ruleRow.addView(textCol)

                // Toggle switch
                val sw = Switch(activity).apply {
                    isChecked = rule.isEnabled
                    val thumbColor = ColorStateList(
                        arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                        intArrayOf(accentColor, if (isDark) Color.GRAY else Color.LTGRAY)
                    )
                    thumbTintList = thumbColor
                    setOnCheckedChangeListener { _, checked ->
                        activity.triggerStrongHaptic()
                        HazeDatabase.getInstance(activity).toggleUserCustomRule(rule.id, checked)
                        HazeFilterListManager.invalidateCache()
                    }
                }
                ruleRow.addView(sw)

                // Delete button
                val ivDelete = ImageView(activity).apply {
                    layoutParams = LinearLayout.LayoutParams(dpToPx(activity, 20), dpToPx(activity, 20)).apply {
                        marginStart = dpToPx(activity, 10)
                    }
                    setImageResource(R.drawable.ic_delete)
                    setColorFilter(Color.parseColor("#EF4444"))
                    setOnClickListener {
                        activity.triggerStrongHaptic()
                        HazeDatabase.getInstance(activity).deleteUserCustomRule(rule.id)
                        HazeFilterListManager.invalidateCache()
                        populateUserRules()
                        Toast.makeText(activity, "Rule removed", Toast.LENGTH_SHORT).show()
                    }
                }
                ruleRow.addView(ivDelete)
                rulesListWrapper.addView(ruleRow)
            }
        }
        populateUserRules()

        btnAddRule.setOnClickListener {
            activity.triggerStrongHaptic()
            showAddRuleDialog(activity, isDark, accentColor) {
                populateUserRules()
            }
        }

        globalContainer.addView(rulesContainer)

        // Tab Switching Logic
        btnTabProtection.setOnClickListener {
            activity.triggerStrongHaptic()
            btnTabProtection.background = tabSelectedBg
            btnTabProtection.setTypeface(null, Typeface.BOLD)
            btnTabProtection.setTextColor(textColor)

            btnTabFilterLists.background = null
            btnTabFilterLists.setTypeface(null, Typeface.NORMAL)
            btnTabFilterLists.setTextColor(subTextColor)

            btnTabRules.background = null
            btnTabRules.setTypeface(null, Typeface.NORMAL)
            btnTabRules.setTextColor(subTextColor)

            protectionContainer.visibility = View.VISIBLE
            filterListsContainer.visibility = View.GONE
            rulesContainer.visibility = View.GONE
        }

        btnTabFilterLists.setOnClickListener {
            activity.triggerStrongHaptic()
            btnTabFilterLists.background = tabSelectedBg
            btnTabFilterLists.setTypeface(null, Typeface.BOLD)
            btnTabFilterLists.setTextColor(textColor)

            btnTabProtection.background = null
            btnTabProtection.setTypeface(null, Typeface.NORMAL)
            btnTabProtection.setTextColor(subTextColor)

            btnTabRules.background = null
            btnTabRules.setTypeface(null, Typeface.NORMAL)
            btnTabRules.setTextColor(subTextColor)

            protectionContainer.visibility = View.GONE
            filterListsContainer.visibility = View.VISIBLE
            rulesContainer.visibility = View.GONE
        }

        btnTabRules.setOnClickListener {
            activity.triggerStrongHaptic()
            btnTabRules.background = tabSelectedBg
            btnTabRules.setTypeface(null, Typeface.BOLD)
            btnTabRules.setTextColor(textColor)

            btnTabProtection.background = null
            btnTabProtection.setTypeface(null, Typeface.NORMAL)
            btnTabProtection.setTextColor(subTextColor)

            btnTabFilterLists.background = null
            btnTabFilterLists.setTypeface(null, Typeface.NORMAL)
            btnTabFilterLists.setTextColor(subTextColor)

            protectionContainer.visibility = View.GONE
            filterListsContainer.visibility = View.GONE
            rulesContainer.visibility = View.VISIBLE
        }

        // Master switch toggle handler
        masterSwitch.setOnCheckedChangeListener { _, isChecked ->
            activity.triggerStrongHaptic()
            HazePrivacyShieldEngine.setShieldEnabled(activity, isChecked)
            tvSub.text = if (isChecked) "Shields UP • Active Protection" else "Shields Disabled"
            tvSub.setTextColor(if (isChecked) accentColor else subTextColor)
            activity.updatePrivacyShieldButton()
        }

        // --- 6. REAL-TIME LIVE UPDATE LISTENER ---
        val shieldListener = object : HazePrivacyShieldEngine.ShieldListener {
            override fun onShieldEvent(category: String, totalBlocked: Long) {
                activity.runOnUiThread {
                    updateSiteTelemetry?.invoke()
                    val currentToday = HazePrivacyShieldEngine.getBlockedToday(activity)
                    tvAdsVal.text = String.format(java.util.Locale.US, "%,d", currentToday.ads)
                    tvTrackersVal.text = String.format(java.util.Locale.US, "%,d", currentToday.trackers)
                    tvPopupsVal.text = String.format(java.util.Locale.US, "%,d", currentToday.popups)
                    tvMalwareVal.text = String.format(java.util.Locale.US, "%,d", currentToday.malware)
                    tvTokensVal.text = String.format(java.util.Locale.US, "%,d", currentToday.tokensCleaned)
                    tvBouncesVal.text = String.format(java.util.Locale.US, "%,d", currentToday.bouncesStopped)
                    tvDataSaved.text = "💾 ${HazePrivacyShieldEngine.formatDataSaved(currentToday.dataSavedBytes)} Saved"
                    tvTimeSaved.text = "⏱️ ${HazePrivacyShieldEngine.formatTimeSaved(currentToday.timeSavedMs)}"
                    refreshGraph()
                }
            }
        }
        HazePrivacyShieldEngine.addListener(shieldListener)

        dialog.setOnDismissListener {
            HazePrivacyShieldEngine.removeListener(shieldListener)
        }

        // --- 7. Reset Button ---
        val btnReset = TextView(activity).apply {
            text = "Reset Statistics"
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(accentColor)
            gravity = Gravity.CENTER
            setPadding(dpToPx(activity, 12), dpToPx(activity, 10), dpToPx(activity, 12), dpToPx(activity, 10))
            background = GradientDrawable().apply {
                cornerRadius = dpToPx(activity, 12).toFloat()
                setColor(alphaAccentColor)
            }
            setOnClickListener {
                activity.triggerStrongHaptic()
                HazePrivacyShieldEngine.resetStats(activity)
                tvAdsVal.text = "0"
                tvTrackersVal.text = "0"
                tvPopupsVal.text = "0"
                tvMalwareVal.text = "0"
                tvTokensVal.text = "0"
                tvBouncesVal.text = "0"
                tvDataSaved.text = "💾 0 B Saved"
                tvTimeSaved.text = "⏱️ 0 seconds saved"
                refreshGraph()
                Toast.makeText(activity, "Shield statistics reset", Toast.LENGTH_SHORT).show()
                activity.updatePrivacyShieldButton()
            }
        }
        globalContainer.addView(btnReset)

        dialog.setContentView(scrollView)
        dialog.show()

        if (initialScrollY > 0) {
            scrollView.postDelayed({
                scrollView.smoothScrollTo(0, dpToPx(activity, initialScrollY))
            }, 300)
        }
        if (openInspect && isRealSite) {
            scrollView.postDelayed({
                showBlockedRequestsDialog(activity, currentDomain ?: "example.com", isDark, accentColor, cardColor, cardBorderColor, textColor, subTextColor)
            }, 350)
        }
    }

    // --- Sub-Dialog: Regional Lists ---
    private fun showRegionalListsDialog(
        activity: MainActivity,
        isDark: Boolean,
        accentColor: Int,
        cardColor: Int,
        cardBorderColor: Int,
        textColor: Int,
        subTextColor: Int
    ) {
        val dialog = BottomSheetDialog(activity)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadii = floatArrayOf(
                    dpToPx(activity, 24).toFloat(), dpToPx(activity, 24).toFloat(),
                    dpToPx(activity, 24).toFloat(), dpToPx(activity, 24).toFloat(),
                    0f, 0f, 0f, 0f
                )
                setColor(if (isDark) Color.parseColor("#1C1C1C") else Color.WHITE)
            }
            setPadding(dpToPx(activity, 20), dpToPx(activity, 16), dpToPx(activity, 20), dpToPx(activity, 24))
        }

        val tvTitle = TextView(activity).apply {
            text = "Regional Filter Lists"
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setTextColor(textColor)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dpToPx(activity, 4)
            }
        }
        val tvSub = TextView(activity).apply {
            text = "Enable regional lists to block local ads and language-specific trackers"
            textSize = 12f
            setTextColor(subTextColor)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dpToPx(activity, 16)
            }
        }
        root.addView(tvTitle)
        root.addView(tvSub)

        for (reg in HazeFilterListManager.REGIONAL_LISTS) {
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = GradientDrawable().apply {
                    cornerRadius = dpToPx(activity, 14).toFloat()
                    setColor(cardColor)
                    setStroke(dpToPx(activity, 1), cardBorderColor)
                }
                setPadding(dpToPx(activity, 14), dpToPx(activity, 10), dpToPx(activity, 14), dpToPx(activity, 10))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dpToPx(activity, 8)
                }
            }

            val textCol = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val tvName = TextView(activity).apply {
                text = "${reg.name} (${reg.country})"
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
                setTextColor(textColor)
            }
            val tvUrl = TextView(activity).apply {
                text = reg.url
                textSize = 11f
                setTextColor(subTextColor)
                maxLines = 1
            }
            textCol.addView(tvName)
            textCol.addView(tvUrl)
            row.addView(textCol)

            val sw = Switch(activity).apply {
                isChecked = HazeFilterListManager.isRegionalListEnabled(activity, reg.id)
                val thumbColor = ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(accentColor, if (isDark) Color.GRAY else Color.LTGRAY)
                )
                thumbTintList = thumbColor
                setOnCheckedChangeListener { _, checked ->
                    activity.triggerStrongHaptic()
                    HazeFilterListManager.setRegionalListEnabled(activity, reg.id, checked)
                }
            }
            row.addView(sw)
            root.addView(row)
        }

        dialog.setContentView(root)
        dialog.show()
    }

    // --- Sub-Dialog: Custom Filter Subscriptions ---
    private fun showCustomSubscriptionsDialog(
        activity: MainActivity,
        isDark: Boolean,
        accentColor: Int,
        cardColor: Int,
        cardBorderColor: Int,
        textColor: Int,
        subTextColor: Int
    ) {
        val dialog = BottomSheetDialog(activity)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadii = floatArrayOf(
                    dpToPx(activity, 24).toFloat(), dpToPx(activity, 24).toFloat(),
                    dpToPx(activity, 24).toFloat(), dpToPx(activity, 24).toFloat(),
                    0f, 0f, 0f, 0f
                )
                setColor(if (isDark) Color.parseColor("#1C1C1C") else Color.WHITE)
            }
            setPadding(dpToPx(activity, 20), dpToPx(activity, 16), dpToPx(activity, 20), dpToPx(activity, 24))
        }

        val tvTitle = TextView(activity).apply {
            text = "Custom Filter Subscriptions"
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setTextColor(textColor)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dpToPx(activity, 4)
            }
        }
        val tvSub = TextView(activity).apply {
            text = "Add external filter list URLs in ABP or uBlock format"
            textSize = 12f
            setTextColor(subTextColor)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dpToPx(activity, 14)
            }
        }
        root.addView(tvTitle)
        root.addView(tvSub)

        val btnAdd = TextView(activity).apply {
            text = "+ Add Subscription URL"
            textSize = 13.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(accentColor)
            gravity = Gravity.CENTER
            setPadding(dpToPx(activity, 14), dpToPx(activity, 10), dpToPx(activity, 14), dpToPx(activity, 10))
            background = GradientDrawable().apply {
                cornerRadius = dpToPx(activity, 12).toFloat()
                setColor(Color.argb(35, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor)))
            }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dpToPx(activity, 14)
            }
        }
        root.addView(btnAdd)

        val listContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(listContainer)

        fun refreshList() {
            listContainer.removeAllViews()
            val subs = HazeDatabase.getInstance(activity).getFilterSubscriptions()
            if (subs.isEmpty()) {
                val tvEmpty = TextView(activity).apply {
                    text = "No custom subscriptions yet.\nTap '+ Add Subscription URL' to add one."
                    textSize = 13f
                    gravity = Gravity.CENTER
                    setTextColor(subTextColor)
                    setPadding(0, dpToPx(activity, 16), 0, dpToPx(activity, 16))
                }
                listContainer.addView(tvEmpty)
                return
            }

            for (sub in subs) {
                val row = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = GradientDrawable().apply {
                        cornerRadius = dpToPx(activity, 14).toFloat()
                        setColor(cardColor)
                        setStroke(dpToPx(activity, 1), cardBorderColor)
                    }
                    setPadding(dpToPx(activity, 12), dpToPx(activity, 10), dpToPx(activity, 12), dpToPx(activity, 10))
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                        bottomMargin = dpToPx(activity, 8)
                    }
                }

                val textCol = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }

                val tvName = TextView(activity).apply {
                    text = sub.name
                    textSize = 14f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(textColor)
                }
                val tvUrl = TextView(activity).apply {
                    text = sub.url
                    textSize = 11f
                    setTextColor(subTextColor)
                    maxLines = 1
                }
                textCol.addView(tvName)
                textCol.addView(tvUrl)
                row.addView(textCol)

                val sw = Switch(activity).apply {
                    isChecked = sub.isEnabled
                    val thumbColor = ColorStateList(
                        arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                        intArrayOf(accentColor, if (isDark) Color.GRAY else Color.LTGRAY)
                    )
                    thumbTintList = thumbColor
                    setOnCheckedChangeListener { _, checked ->
                        activity.triggerStrongHaptic()
                        HazeDatabase.getInstance(activity).setSubscriptionEnabled(sub.id, checked)
                        HazeFilterListManager.invalidateCache()
                    }
                }
                row.addView(sw)

                val ivDel = ImageView(activity).apply {
                    layoutParams = LinearLayout.LayoutParams(dpToPx(activity, 20), dpToPx(activity, 20)).apply {
                        marginStart = dpToPx(activity, 10)
                    }
                    setImageResource(R.drawable.ic_delete)
                    setColorFilter(Color.parseColor("#EF4444"))
                    setOnClickListener {
                        activity.triggerStrongHaptic()
                        HazeDatabase.getInstance(activity).deleteCustomSubscription(sub.id)
                        HazeFilterListManager.invalidateCache()
                        refreshList()
                        Toast.makeText(activity, "Subscription deleted", Toast.LENGTH_SHORT).show()
                    }
                }
                row.addView(ivDel)
                listContainer.addView(row)
            }
        }
        refreshList()

        btnAdd.setOnClickListener {
            activity.triggerStrongHaptic()
            showAddSubscriptionDialog(activity, isDark, accentColor) {
                refreshList()
            }
        }

        dialog.setContentView(root)
        dialog.show()
    }

    // --- Sub-Dialog: Add Subscription Prompt ---
    private fun showAddSubscriptionDialog(activity: MainActivity, isDark: Boolean, accentColor: Int, onAdded: () -> Unit) {
        val builder = AlertDialog.Builder(activity)
        builder.setTitle("Add Custom Filter List")

        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(activity, 20), dpToPx(activity, 12), dpToPx(activity, 20), dpToPx(activity, 12))
        }

        val etName = EditText(activity).apply {
            hint = "List Name (e.g., My Blocklist)"
            setSingleLine(true)
        }
        val etUrl = EditText(activity).apply {
            hint = "https://example.com/filter.txt"
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
        }
        layout.addView(etName)
        layout.addView(etUrl)
        builder.setView(layout)

        builder.setPositiveButton("Add") { _, _ ->
            val name = etName.text.toString().trim()
            val url = etUrl.text.toString().trim()
            if (url.startsWith("http://") || url.startsWith("https://")) {
                val ok = HazeDatabase.getInstance(activity).addCustomSubscription(name, url)
                if (ok) {
                    HazeFilterListManager.invalidateCache()
                    Toast.makeText(activity, "Subscription added!", Toast.LENGTH_SHORT).show()
                    onAdded()
                } else {
                    Toast.makeText(activity, "Failed to add subscription", Toast.LENGTH_SHORT).show()
                }
            } else {
                Toast.makeText(activity, "Please enter a valid HTTP/HTTPS URL", Toast.LENGTH_SHORT).show()
            }
        }
        builder.setNegativeButton("Cancel", null)
        builder.show()
    }

    // --- Sub-Dialog: Add Custom Rule Prompt ---
    private fun showAddRuleDialog(activity: MainActivity, isDark: Boolean, accentColor: Int, onAdded: () -> Unit) {
        val builder = AlertDialog.Builder(activity)
        builder.setTitle("Add Custom Rule")

        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(activity, 20), dpToPx(activity, 12), dpToPx(activity, 20), dpToPx(activity, 12))
        }

        val etRule = EditText(activity).apply {
            hint = "e.g., ||example.com^ or ##.banner"
            setSingleLine(true)
        }
        layout.addView(etRule)
        builder.setView(layout)

        builder.setPositiveButton("Add Rule") { _, _ ->
            val rule = etRule.text.toString().trim()
            if (rule.isNotBlank()) {
                val ok = HazeDatabase.getInstance(activity).addUserCustomRule(rule)
                if (ok) {
                    HazeFilterListManager.invalidateCache()
                    Toast.makeText(activity, "Rule added!", Toast.LENGTH_SHORT).show()
                    onAdded()
                } else {
                    Toast.makeText(activity, "Failed to add rule", Toast.LENGTH_SHORT).show()
                }
            }
        }
        builder.setNegativeButton("Cancel", null)
        builder.show()
    }

    // =========================================================================
    // PER-SITE SIGNATURE PRIVACY DASHBOARD VIEW
    // =========================================================================
    private fun buildSitePrivacyView(
        activity: MainActivity,
        currentUrl: String,
        isDark: Boolean,
        textColor: Int,
        subTextColor: Int,
        cardColor: Int,
        cardBorderColor: Int,
        accentColor: Int,
        alphaAccentColor: Int,
        onSwitchToGlobal: () -> Unit
    ): Pair<View, () -> Unit> {
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        var report = HazePrivacyShieldEngine.getSiteReport(activity, currentUrl)
        val host = report.host

        // 1. Site Header & Shield Status Card
        val siteHeaderCard = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dpToPx(activity, 18).toFloat()
                setColor(cardColor)
                setStroke(dpToPx(activity, 1), cardBorderColor)
            }
            setPadding(dpToPx(activity, 16), dpToPx(activity, 14), dpToPx(activity, 16), dpToPx(activity, 14))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dpToPx(activity, 14)
            }
        }

        val ivGlobe = ImageView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(dpToPx(activity, 24), dpToPx(activity, 24)).apply {
                marginEnd = dpToPx(activity, 12)
            }
            setImageResource(R.drawable.ic_globe)
            setColorFilter(if (report.isShieldEnabledForSite) accentColor else subTextColor)
        }
        siteHeaderCard.addView(ivGlobe)

        val siteTextCol = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val tvSiteTitle = TextView(activity).apply {
            text = host
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(textColor)
            ellipsize = android.text.TextUtils.TruncateAt.END
            maxLines = 1
        }
        val tvSiteSub = TextView(activity).apply {
            text = if (report.isShieldEnabledForSite) "Shields UP • Protected" else "Shields Paused for this site"
            textSize = 12f
            setTextColor(if (report.isShieldEnabledForSite) Color.parseColor("#10B981") else Color.parseColor("#E65100"))
        }
        siteTextCol.addView(tvSiteTitle)
        siteTextCol.addView(tvSiteSub)
        siteHeaderCard.addView(siteTextCol)

        val siteMasterSwitch = Switch(activity).apply {
            isChecked = report.isShieldEnabledForSite
            val thumbColor = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(accentColor, if (isDark) Color.GRAY else Color.LTGRAY)
            )
            thumbTintList = thumbColor
        }
        siteHeaderCard.addView(siteMasterSwitch)
        root.addView(siteHeaderCard)

        // 2. Privacy Score Card
        // Displays:
        // example.com
        // Privacy Score
        // ████████░░
        // 82%
        val scoreCard = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dpToPx(activity, 20).toFloat()
                setColor(cardColor)
                setStroke(dpToPx(activity, 1), cardBorderColor)
            }
            setPadding(dpToPx(activity, 18), dpToPx(activity, 18), dpToPx(activity, 18), dpToPx(activity, 18))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dpToPx(activity, 14)
            }
        }

        val scoreTitleRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dpToPx(activity, 10)
            }
        }
        val tvScoreLabel = TextView(activity).apply {
            text = "Privacy Score"
            textSize = 15.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(textColor)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val tvScoreBadge = TextView(activity).apply {
            textSize = 11.5f
            setTypeface(null, Typeface.BOLD)
            setPadding(dpToPx(activity, 8), dpToPx(activity, 3), dpToPx(activity, 8), dpToPx(activity, 3))
        }
        scoreTitleRow.addView(tvScoreLabel)
        scoreTitleRow.addView(tvScoreBadge)
        scoreCard.addView(scoreTitleRow)

        // ASCII Meter row: ████████░░
        val tvAsciiBar = TextView(activity).apply {
            text = report.asciiScoreBar
            textSize = 21f
            setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            letterSpacing = 0.12f
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dpToPx(activity, 6)
            }
        }
        scoreCard.addView(tvAsciiBar)

        // Score percentage row: 82%
        val tvScorePercent = TextView(activity).apply {
            text = "${report.privacyScore}%"
            textSize = 34f
            setTypeface(null, Typeface.BOLD)
            setTextColor(textColor)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dpToPx(activity, 10)
            }
        }
        scoreCard.addView(tvScorePercent)

        // Graphical Progress Bar Track
        val progressTrack = FrameLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(activity, 8)).apply {
                bottomMargin = dpToPx(activity, 10)
            }
            background = GradientDrawable().apply {
                cornerRadius = dpToPx(activity, 4).toFloat()
                setColor(if (isDark) Color.parseColor("#333333") else Color.parseColor("#E0E0E0"))
            }
        }
        val progressFill = View(activity).apply {
            background = GradientDrawable().apply {
                cornerRadius = dpToPx(activity, 4).toFloat()
            }
        }
        progressTrack.addView(progressFill)
        scoreCard.addView(progressTrack)

        val tvScoreSummary = TextView(activity).apply {
            textSize = 12f
            setTextColor(subTextColor)
        }
        scoreCard.addView(tvScoreSummary)
        root.addView(scoreCard)

        // Helper to update score aesthetics (Grade badge, Ascii bar color, progress bar)
        fun renderScoreDetails(score: Int, asciiBar: String) {
            val scoreColor = when {
                score >= 80 -> Color.parseColor("#10B981") // Emerald Green
                score >= 65 -> Color.parseColor("#3B82F6") // Blue
                score >= 50 -> Color.parseColor("#F59E0B") // Amber
                else -> Color.parseColor("#EF4444") // Coral Red
            }
            val gradeText = when {
                score >= 80 -> "Grade A • Excellent"
                score >= 65 -> "Grade B • Good"
                score >= 50 -> "Grade C • Moderate"
                else -> "Grade D • High Exposure"
            }
            tvScoreBadge.text = gradeText
            tvScoreBadge.setTextColor(scoreColor)
            tvScoreBadge.background = GradientDrawable().apply {
                cornerRadius = dpToPx(activity, 8).toFloat()
                setColor(Color.argb(35, Color.red(scoreColor), Color.green(scoreColor), Color.blue(scoreColor)))
            }
            tvAsciiBar.text = asciiBar
            tvAsciiBar.setTextColor(scoreColor)
            tvScorePercent.text = "$score%"

            progressTrack.post {
                val trackWidth = progressTrack.width
                val fillWidth = (trackWidth * (score.coerceIn(0, 100) / 100f)).toInt()
                progressFill.layoutParams = FrameLayout.LayoutParams(fillWidth, FrameLayout.LayoutParams.MATCH_PARENT)
                (progressFill.background as? GradientDrawable)?.setColor(scoreColor)
            }

            tvScoreSummary.text = if (score >= 80) {
                "High privacy protection active. Tracking networks and fingerprinting probes blocked."
            } else {
                "Site requires active protection. Multiple tracking beacons and scripts intercepted."
            }
        }
        renderScoreDetails(report.privacyScore, report.asciiScoreBar)

        // 3. Protection Telemetry Card
        // Displays:
        // Trackers blocked       14
        // Ads blocked             31
        // Cookies blocked          8
        // Fingerprint attempts     2
        // HTTPS                   Yes
        val telemetryCard = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dpToPx(activity, 20).toFloat()
                setColor(cardColor)
                setStroke(dpToPx(activity, 1), cardBorderColor)
            }
            setPadding(dpToPx(activity, 16), dpToPx(activity, 16), dpToPx(activity, 16), dpToPx(activity, 16))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dpToPx(activity, 14)
            }
        }

        val tvTelemetryTitle = TextView(activity).apply {
            text = "Protection Telemetry"
            textSize = 15.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(textColor)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dpToPx(activity, 12)
            }
        }
        telemetryCard.addView(tvTelemetryTitle)

        fun createTelemetryRow(label: String, initialVal: String, valueColor: Int, showDivider: Boolean = true): Pair<LinearLayout, TextView> {
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dpToPx(activity, 7)
                    bottomMargin = dpToPx(activity, 7)
                }
            }

            val tvLbl = TextView(activity).apply {
                text = label
                textSize = 13.5f
                setTextColor(subTextColor)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val tvVal = TextView(activity).apply {
                text = initialVal
                textSize = 14.5f
                setTypeface(null, Typeface.BOLD)
                setTextColor(valueColor)
            }
            row.addView(tvLbl)
            row.addView(tvVal)

            val wrapper = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            wrapper.addView(row)
            if (showDivider) {
                val div = View(activity).apply {
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(activity, 1)).apply {
                        topMargin = dpToPx(activity, 4)
                    }
                    setBackgroundColor(if (isDark) Color.parseColor("#2A2A2A") else Color.parseColor("#EAEAEA"))
                }
                wrapper.addView(div)
            }
            return Pair(wrapper, tvVal)
        }

        val (rowTrackers, tvTrackersVal) = createTelemetryRow("Trackers blocked", report.trackersBlocked.toString(), Color.parseColor("#8B5CF6"))
        val (rowAds, tvAdsVal) = createTelemetryRow("Ads blocked", report.adsBlocked.toString(), accentColor)
        val (rowCookies, tvCookiesVal) = createTelemetryRow("Cookies blocked", report.cookiesBlocked.toString(), Color.parseColor("#F59E0B"))
        val (rowFingerprints, tvFingerprintsVal) = createTelemetryRow("Fingerprint attempts", report.fingerprintAttempts.toString(), Color.parseColor("#EF4444"))
        val (rowHttps, tvHttpsVal) = createTelemetryRow("HTTPS", if (report.isHttps) "Yes" else "No", if (report.isHttps) Color.parseColor("#10B981") else Color.parseColor("#EF4444"), showDivider = false)

        telemetryCard.addView(rowTrackers)
        telemetryCard.addView(rowAds)
        telemetryCard.addView(rowCookies)
        telemetryCard.addView(rowFingerprints)
        telemetryCard.addView(rowHttps)
        root.addView(telemetryCard)

        // 4. Permissions Card
        // Displays:
        // Permissions
        // Camera       Denied
        // Microphone   Denied
        // Location     Ask
        // Notifications Denied
        val permissionsCard = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dpToPx(activity, 20).toFloat()
                setColor(cardColor)
                setStroke(dpToPx(activity, 1), cardBorderColor)
            }
            setPadding(dpToPx(activity, 16), dpToPx(activity, 16), dpToPx(activity, 16), dpToPx(activity, 16))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dpToPx(activity, 14)
            }
        }

        val permTitleRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dpToPx(activity, 12)
            }
        }
        val tvPermHeader = TextView(activity).apply {
            text = "Permissions"
            textSize = 15.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(textColor)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val tvPermHint = TextView(activity).apply {
            text = "Tap to toggle"
            textSize = 11.5f
            setTextColor(subTextColor)
        }
        permTitleRow.addView(tvPermHeader)
        permTitleRow.addView(tvPermHint)
        permissionsCard.addView(permTitleRow)

        fun createPermissionRow(type: HazeSitePermissionManager.PermissionType, showDivider: Boolean = true): LinearLayout {
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dpToPx(activity, 8)
                    bottomMargin = dpToPx(activity, 8)
                }
            }

            val tvLbl = TextView(activity).apply {
                text = type.displayName
                textSize = 14f
                setTextColor(textColor)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val tvPill = TextView(activity).apply {
                textSize = 12f
                setTypeface(null, Typeface.BOLD)
                setPadding(dpToPx(activity, 10), dpToPx(activity, 4), dpToPx(activity, 10), dpToPx(activity, 4))
            }

            fun updatePill(state: HazeSitePermissionManager.State) {
                tvPill.text = state.label
                when (state) {
                    HazeSitePermissionManager.State.DENIED -> {
                        tvPill.setTextColor(Color.parseColor("#EF4444"))
                        tvPill.background = GradientDrawable().apply {
                            cornerRadius = dpToPx(activity, 8).toFloat()
                            setColor(if (isDark) Color.parseColor("#341B1B") else Color.parseColor("#FEE2E2"))
                        }
                    }
                    HazeSitePermissionManager.State.ASK -> {
                        tvPill.setTextColor(Color.parseColor("#F59E0B"))
                        tvPill.background = GradientDrawable().apply {
                            cornerRadius = dpToPx(activity, 8).toFloat()
                            setColor(if (isDark) Color.parseColor("#342C1B") else Color.parseColor("#FEF3C7"))
                        }
                    }
                    HazeSitePermissionManager.State.ALLOWED -> {
                        tvPill.setTextColor(Color.parseColor("#10B981"))
                        tvPill.background = GradientDrawable().apply {
                            cornerRadius = dpToPx(activity, 8).toFloat()
                            setColor(if (isDark) Color.parseColor("#1B3426") else Color.parseColor("#D1FAE5"))
                        }
                    }
                }
            }

            val currentPerm = HazeSitePermissionManager.getPermission(activity, host, type)
            updatePill(currentPerm)

            row.setOnClickListener {
                activity.triggerStrongHaptic()
                val next = HazeSitePermissionManager.cyclePermission(activity, host, type)
                updatePill(next)
                Toast.makeText(activity, "${type.displayName}: ${next.label}", Toast.LENGTH_SHORT).show()
                // Recalculate score as permissions contribute to privacy score
                val rep = HazePrivacyShieldEngine.getSiteReport(activity, currentUrl)
                renderScoreDetails(rep.privacyScore, rep.asciiScoreBar)
            }

            row.addView(tvLbl)
            row.addView(tvPill)

            val wrapper = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            }
            wrapper.addView(row)
            if (showDivider) {
                val div = View(activity).apply {
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(activity, 1)).apply {
                        topMargin = dpToPx(activity, 4)
                    }
                    setBackgroundColor(if (isDark) Color.parseColor("#2A2A2A") else Color.parseColor("#EAEAEA"))
                }
                wrapper.addView(div)
            }
            return wrapper
        }

        permissionsCard.addView(createPermissionRow(HazeSitePermissionManager.PermissionType.CAMERA))
        permissionsCard.addView(createPermissionRow(HazeSitePermissionManager.PermissionType.MICROPHONE))
        permissionsCard.addView(createPermissionRow(HazeSitePermissionManager.PermissionType.LOCATION))
        permissionsCard.addView(createPermissionRow(HazeSitePermissionManager.PermissionType.NOTIFICATIONS, showDivider = false))
        root.addView(permissionsCard)

        // 5. "Why did we block this?" Educational Explanations & Inspection
        // "This makes privacy understandable instead of turning it into a pile of technical switches."
        val whyCard = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dpToPx(activity, 20).toFloat()
                setColor(cardColor)
                setStroke(dpToPx(activity, 1), cardBorderColor)
            }
            setPadding(dpToPx(activity, 16), dpToPx(activity, 16), dpToPx(activity, 16), dpToPx(activity, 16))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dpToPx(activity, 16)
            }
        }

        val tvWhyTitle = TextView(activity).apply {
            text = "Why did we block this?"
            textSize = 15.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(textColor)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dpToPx(activity, 4)
            }
        }
        val tvWhySubtitle = TextView(activity).apply {
            text = "This makes privacy understandable instead of turning it into a pile of technical switches."
            textSize = 12f
            setTextColor(subTextColor)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dpToPx(activity, 14)
            }
        }
        whyCard.addView(tvWhyTitle)
        whyCard.addView(tvWhySubtitle)

        fun createWhyItem(icon: String, title: String, explanation: String): LinearLayout {
            val itemRow = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dpToPx(activity, 12)
                }
            }

            val tvIcon = TextView(activity).apply {
                text = icon
                textSize = 17f
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = dpToPx(activity, 10)
                    topMargin = dpToPx(activity, 1)
                }
            }

            val textCol = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val tvH = TextView(activity).apply {
                text = title
                textSize = 13.5f
                setTypeface(null, Typeface.BOLD)
                setTextColor(textColor)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dpToPx(activity, 2)
                }
            }
            val tvB = TextView(activity).apply {
                text = explanation
                textSize = 12f
                setTextColor(subTextColor)
                setLineSpacing(dpToPx(activity, 2).toFloat(), 1f)
            }
            textCol.addView(tvH)
            textCol.addView(tvB)
            itemRow.addView(tvIcon)
            itemRow.addView(textCol)
            return itemRow
        }

        whyCard.addView(createWhyItem("🛡️", "Trackers Blocked", "Third-party scripts (Google Analytics, Meta Pixel, TikTok, Criteo) secretly record your clicks, mouse movements, purchase intent, and cross-site habits to profile you."))
        whyCard.addView(createWhyItem("🚫", "Ads Blocked", "Intrusive banners, popunders, and sponsored widgets slow down page load by up to 60%, drain battery, and consume mobile data without your consent."))
        whyCard.addView(createWhyItem("🍪", "Cookies Blocked", "Cross-domain tracking cookies attempt to link your activity on this website with your profiles across social networks and advertising exchanges."))
        whyCard.addView(createWhyItem("🔍", "Fingerprint Attempts", "Stealth JavaScript queries interrogating your device's HTML5 Canvas, WebGL shaders, Audio buffer, and system fonts to uniquely identify you without cookies."))

        // Inspect All Blocked Requests Button
        val btnInspect = TextView(activity).apply {
            val totalCount = report.trackersBlocked + report.adsBlocked + report.cookiesBlocked + report.fingerprintAttempts
            text = "Inspect All Blocked Requests ($totalCount)"
            textSize = 13.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(accentColor)
            gravity = Gravity.CENTER
            setPadding(dpToPx(activity, 14), dpToPx(activity, 12), dpToPx(activity, 14), dpToPx(activity, 12))
            background = GradientDrawable().apply {
                cornerRadius = dpToPx(activity, 14).toFloat()
                setColor(alphaAccentColor)
            }
            setOnClickListener {
                activity.triggerStrongHaptic()
                showBlockedRequestsDialog(activity, host, isDark, accentColor, cardColor, cardBorderColor, textColor, subTextColor)
            }
        }
        whyCard.addView(btnInspect)
        root.addView(whyCard)

        // Master switch toggle handler for this specific site
        siteMasterSwitch.setOnCheckedChangeListener { _, checked ->
            activity.triggerStrongHaptic()
            HazePrivacyShieldEngine.setHostWhitelisted(activity, host, !checked)
            tvSiteSub.text = if (checked) "Shields UP • Protected" else "Shields Paused for this site"
            tvSiteSub.setTextColor(if (checked) Color.parseColor("#10B981") else Color.parseColor("#E65100"))
            ivGlobe.setColorFilter(if (checked) accentColor else subTextColor)
            Toast.makeText(
                activity,
                if (checked) "Shields enabled for $host" else "Shields paused for $host",
                Toast.LENGTH_SHORT
            ).show()
            activity.getActiveTab()?.browserTab?.navigationController?.reload()
            activity.updatePrivacyShieldButton()
        }

        // Live updater callback
        val updateFn: () -> Unit = {
            val rep = HazePrivacyShieldEngine.getSiteReport(activity, currentUrl)
            tvTrackersVal.text = rep.trackersBlocked.toString()
            tvAdsVal.text = rep.adsBlocked.toString()
            tvCookiesVal.text = rep.cookiesBlocked.toString()
            tvFingerprintsVal.text = rep.fingerprintAttempts.toString()
            val totalCount = rep.trackersBlocked + rep.adsBlocked + rep.cookiesBlocked + rep.fingerprintAttempts
            btnInspect.text = "Inspect All Blocked Requests ($totalCount)"
            renderScoreDetails(rep.privacyScore, rep.asciiScoreBar)
        }

        return Pair(root, updateFn)
    }

    // =========================================================================
    // SUB-DIALOG: INSPECT BLOCKED REQUESTS
    // =========================================================================
    private fun showBlockedRequestsDialog(
        activity: MainActivity,
        host: String,
        isDark: Boolean,
        accentColor: Int,
        cardColor: Int,
        cardBorderColor: Int,
        textColor: Int,
        subTextColor: Int
    ) {
        val dialog = BottomSheetDialog(activity)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(activity, 20), dpToPx(activity, 12), dpToPx(activity, 20), dpToPx(activity, 24))
            background = GradientDrawable().apply {
                cornerRadii = floatArrayOf(
                    dpToPx(activity, 24).toFloat(), dpToPx(activity, 24).toFloat(),
                    dpToPx(activity, 24).toFloat(), dpToPx(activity, 24).toFloat(),
                    0f, 0f, 0f, 0f
                )
                setColor(if (isDark) Color.parseColor("#1E1E1E") else Color.WHITE)
            }
        }

        val handleWrapper = FrameLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(activity, 16)).apply {
                bottomMargin = dpToPx(activity, 8)
            }
        }
        val handle = View(activity).apply {
            layoutParams = FrameLayout.LayoutParams(dpToPx(activity, 36), dpToPx(activity, 4)).apply {
                gravity = Gravity.CENTER
            }
            background = GradientDrawable().apply {
                cornerRadius = dpToPx(activity, 2).toFloat()
                setColor(if (isDark) Color.parseColor("#444444") else Color.parseColor("#D0D0D0"))
            }
        }
        handleWrapper.addView(handle)
        root.addView(handleWrapper)

        val headerRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dpToPx(activity, 14)
            }
        }
        val tvTitle = TextView(activity).apply {
            text = "Blocked on $host"
            textSize = 17f
            setTypeface(null, Typeface.BOLD)
            setTextColor(textColor)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val items = HazePrivacyShieldEngine.getBlockedItemsForHost(host)
        val tvCount = TextView(activity).apply {
            text = "${items.size} blocked"
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(accentColor)
            background = GradientDrawable().apply {
                cornerRadius = dpToPx(activity, 8).toFloat()
                setColor(Color.argb(35, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor)))
            }
            setPadding(dpToPx(activity, 8), dpToPx(activity, 3), dpToPx(activity, 8), dpToPx(activity, 3))
        }
        headerRow.addView(tvTitle)
        headerRow.addView(tvCount)
        root.addView(headerRow)

        if (items.isEmpty()) {
            val tvEmpty = TextView(activity).apply {
                text = "No requests have been blocked on this page yet.\nAll scripts and connections are verified clean."
                textSize = 13.5f
                setTextColor(subTextColor)
                gravity = Gravity.CENTER
                setPadding(0, dpToPx(activity, 32), 0, dpToPx(activity, 32))
            }
            root.addView(tvEmpty)
        } else {
            val scroll = NestedScrollView(activity).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(activity, 380))
            }
            val list = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
            }

            items.forEach { item ->
                val card = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    background = GradientDrawable().apply {
                        cornerRadius = dpToPx(activity, 14).toFloat()
                        setColor(cardColor)
                        setStroke(dpToPx(activity, 1), cardBorderColor)
                    }
                    setPadding(dpToPx(activity, 12), dpToPx(activity, 10), dpToPx(activity, 12), dpToPx(activity, 10))
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                        bottomMargin = dpToPx(activity, 8)
                    }
                }

                val topRow = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                        bottomMargin = dpToPx(activity, 4)
                    }
                }

                val badgeColor = when (item.category.lowercase()) {
                    "tracker", "tracking" -> Color.parseColor("#8B5CF6")
                    "ad", "advertisement" -> accentColor
                    "cookie" -> Color.parseColor("#F59E0B")
                    "fingerprint", "fingerprinting" -> Color.parseColor("#EF4444")
                    else -> Color.parseColor("#10B981")
                }

                val tvBadge = TextView(activity).apply {
                    text = item.category.uppercase()
                    textSize = 10f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(badgeColor)
                    background = GradientDrawable().apply {
                        cornerRadius = dpToPx(activity, 6).toFloat()
                        setColor(Color.argb(35, Color.red(badgeColor), Color.green(badgeColor), Color.blue(badgeColor)))
                    }
                    setPadding(dpToPx(activity, 6), dpToPx(activity, 2), dpToPx(activity, 6), dpToPx(activity, 2))
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                        marginEnd = dpToPx(activity, 8)
                    }
                }

                val tvDomain = TextView(activity).apply {
                    text = item.domain.ifEmpty { item.url }
                    textSize = 13f
                    setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
                    setTextColor(textColor)
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    maxLines = 1
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                topRow.addView(tvBadge)
                topRow.addView(tvDomain)
                card.addView(topRow)

                val tvExplanation = TextView(activity).apply {
                    text = item.humanExplanation.ifEmpty { item.reason }
                    textSize = 11.5f
                    setTextColor(subTextColor)
                }
                card.addView(tvExplanation)
                list.addView(card)
            }

            scroll.addView(list)
            root.addView(scroll)
        }

        dialog.setContentView(root)
        dialog.show()
    }
}
