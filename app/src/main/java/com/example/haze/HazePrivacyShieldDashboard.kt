package com.example.haze

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.*
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
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

            // 1. Vertical Grid Lines & Timeline Labels (No Clipping on Left or Right)
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
                val normY = dataPoints.getOrElse(index) { 0.05f }
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

    fun showDashboard(activity: MainActivity) {
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

        var selectedCategory = "All"
        var selectedRange = "7 days"

        val dialog = BottomSheetDialog(activity)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        dialog.setOnShowListener {
            val bottomSheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            if (bottomSheet != null) {
                val behavior = BottomSheetBehavior.from(bottomSheet)
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
            }
        }

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadii = floatArrayOf(
                    dpToPx(activity, 28).toFloat(), dpToPx(activity, 28).toFloat(),
                    dpToPx(activity, 28).toFloat(), dpToPx(activity, 28).toFloat(),
                    0f, 0f, 0f, 0f
                )
                setColor(bgColor)
            }
            setPadding(dpToPx(activity, 20), dpToPx(activity, 8), dpToPx(activity, 20), dpToPx(activity, 28))
        }

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
        root.addView(handleWrapper)

        // NestedScrollView for smooth vertical scrolling
        val scrollView = NestedScrollView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            isVerticalScrollBarEnabled = false
        }

        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        scrollView.addView(content)
        root.addView(scrollView)

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
            text = if (HazePrivacyShieldEngine.isShieldEnabled(activity)) "Protection Active" else "Protection Disabled"
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
        content.addView(headerRow)

        // --- 2. Real Session & Persistent Database Stats Card ---
        val dbStats = HazeDatabase.getInstance(activity).getPrivacyStats()
        val rawStats = HazePrivacyShieldEngine.getSessionStats()
        val rawTotal = HazePrivacyShieldEngine.getTotalBlocked(activity)

        val effectiveAds = dbStats.adsBlocked + rawStats.adsBlocked + dbStats.unwantedScriptsBlocked + rawStats.unwantedScriptsBlocked
        val effectiveTrackers = dbStats.trackersBlocked + rawStats.trackersBlocked + dbStats.thirdPartyBlocked + rawStats.thirdPartyBlocked + dbStats.maliciousBlocked + rawStats.maliciousBlocked + dbStats.phishingBlocked + rawStats.phishingBlocked
        val effectiveTotal = maxOf(rawTotal, effectiveAds + effectiveTrackers)

        val heroCard = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dpToPx(activity, 18).toFloat()
                setColor(cardColor)
                setStroke(dpToPx(activity, 1), cardBorderColor)
            }
            setPadding(dpToPx(activity, 16), dpToPx(activity, 16), dpToPx(activity, 16), dpToPx(activity, 16))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dpToPx(activity, 18)
            }
        }

        var tvAdsVal: TextView
        var tvTrackersVal: TextView
        var tvTotalVal: TextView

        fun createStatColumn(label: String, initialVal: String): Pair<LinearLayout, TextView> {
            val tvVal = TextView(activity).apply {
                text = initialVal
                textSize = 21f
                setTypeface(null, Typeface.BOLD)
                setTextColor(accentColor)
            }
            val tvLbl = TextView(activity).apply {
                text = label
                textSize = 11.5f
                setTextColor(subTextColor)
            }
            val col = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                addView(tvVal)
                addView(tvLbl)
            }
            return Pair(col, tvVal)
        }

        val (adsCol, tvAds) = createStatColumn("Ads Blocked", String.format(java.util.Locale.US, "%,d", effectiveAds))
        val (trackersCol, tvTrackers) = createStatColumn("Trackers Blocked", String.format(java.util.Locale.US, "%,d", effectiveTrackers))
        val (totalCol, tvTotal) = createStatColumn("Total Shielded", String.format(java.util.Locale.US, "%,d", effectiveTotal))

        tvAdsVal = tvAds
        tvTrackersVal = tvTrackers
        tvTotalVal = tvTotal

        heroCard.addView(adsCol)
        heroCard.addView(trackersCol)
        heroCard.addView(totalCol)
        content.addView(heroCard)

        // --- 3. Filter Dropdown Chips ---
        val filterRow = RelativeLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dpToPx(activity, 8)
            }
        }

        val chipBg = GradientDrawable().apply {
            cornerRadius = dpToPx(activity, 12).toFloat()
            setColor(if (isDark) Color.parseColor("#282828") else Color.parseColor("#F0F2F5"))
        }

        val tvFilterAll = TextView(activity).apply {
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
            background = chipBg
            setPadding(dpToPx(activity, 14), dpToPx(activity, 6), dpToPx(activity, 14), dpToPx(activity, 6))
            layoutParams = RelativeLayout.LayoutParams(RelativeLayout.LayoutParams.WRAP_CONTENT, RelativeLayout.LayoutParams.WRAP_CONTENT).apply {
                addRule(RelativeLayout.ALIGN_PARENT_END)
            }
        }
        filterRow.addView(tvFilterAll)
        filterRow.addView(tvFilterDays)
        content.addView(filterRow)

        // --- 4. Protection Smooth Graph ---
        val graphView = ProtectionGraphView(activity, accentColor, isDark).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(activity, 190)).apply {
                bottomMargin = dpToPx(activity, 18)
            }
        }
        content.addView(graphView)

        fun refreshGraph() {
            val (points, labels) = computeGraphDataset(activity, selectedCategory, selectedRange)
            graphView.updateData(points, labels)
        }
        refreshGraph()

        // Category Filter Menu Click ("All ▾")
        tvFilterAll.setOnClickListener {
            activity.triggerStrongHaptic()
            val popup = PopupMenu(activity, tvFilterAll)
            val categories = listOf("All", "Ads", "Trackers", "CDN / Other")
            categories.forEach { cat -> popup.menu.add(cat) }
            popup.setOnMenuItemClickListener { item ->
                selectedCategory = item.title.toString()
                tvFilterAll.text = "$selectedCategory  ▾"
                refreshGraph()
                true
            }
            popup.show()
        }

        // Time Range Filter Menu Click ("7 days ▾")
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

        // --- 5. Actions / Statistics Segmented Control ---
        val tabPill = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                cornerRadius = dpToPx(activity, 14).toFloat()
                setColor(if (isDark) Color.parseColor("#282828") else Color.parseColor("#EFEFEF"))
            }
            setPadding(dpToPx(activity, 4), dpToPx(activity, 4), dpToPx(activity, 4), dpToPx(activity, 4))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(activity, 44)).apply {
                bottomMargin = dpToPx(activity, 16)
            }
        }

        val btnActions = TextView(activity).apply {
            text = "Actions"
            textSize = 13.5f
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.NORMAL)
            setTextColor(subTextColor)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
        }

        val btnStatistics = TextView(activity).apply {
            text = "Statistics"
            textSize = 13.5f
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
            setTextColor(textColor)
            background = GradientDrawable().apply {
                cornerRadius = dpToPx(activity, 10).toFloat()
                setColor(if (isDark) Color.parseColor("#383838") else Color.WHITE)
            }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
        }

        tabPill.addView(btnActions)
        tabPill.addView(btnStatistics)
        content.addView(tabPill)

        // Actions Container
        val actionsContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
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

        fun createToggleRow(title: String, prefKey: String, defaultVal: Boolean = true): LinearLayout {
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dpToPx(activity, 10), 0, dpToPx(activity, 10))
            }
            val tv = TextView(activity).apply {
                text = title
                textSize = 14f
                setTextColor(textColor)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
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
                }
            }
            row.addView(tv)
            row.addView(sw)
            return row
        }

        actionsContainer.addView(createToggleRow("Block Advertisements", HazePrivacyShieldEngine.PREF_BLOCK_ADS, true))
        actionsContainer.addView(createToggleRow("Block Trackers & Analytics", HazePrivacyShieldEngine.PREF_BLOCK_TRACKERS, true))
        actionsContainer.addView(createToggleRow("Block Malicious Popups", HazePrivacyShieldEngine.PREF_BLOCK_MALWARE, true))

        // Statistics Container (REAL STATS)
        val statisticsContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.VISIBLE
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dpToPx(activity, 16)
            }
        }

        var tvAdsRowVal: TextView
        var tvTrackersRowVal: TextView
        var tvCdnRowVal: TextView
        var tvDataRowVal: TextView

        fun createBreakdownRow(iconRes: Int, label: String, valText: String): Pair<LinearLayout, TextView> {
            val row = RelativeLayout(activity).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    setPadding(dpToPx(activity, 4), dpToPx(activity, 10), dpToPx(activity, 4), dpToPx(activity, 10))
                }
            }
            val leftCol = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = RelativeLayout.LayoutParams(RelativeLayout.LayoutParams.WRAP_CONTENT, RelativeLayout.LayoutParams.WRAP_CONTENT).apply {
                    addRule(RelativeLayout.ALIGN_PARENT_START)
                }
            }
            val ic = ImageView(activity).apply {
                layoutParams = LinearLayout.LayoutParams(dpToPx(activity, 18), dpToPx(activity, 18)).apply {
                    marginEnd = dpToPx(activity, 10)
                }
                setImageResource(iconRes)
                setColorFilter(subTextColor)
            }
            val tvLabel = TextView(activity).apply {
                text = label
                textSize = 14f
                setTextColor(textColor)
            }
            leftCol.addView(ic)
            leftCol.addView(tvLabel)

            val tvVal = TextView(activity).apply {
                text = valText
                textSize = 14.5f
                setTypeface(null, Typeface.BOLD)
                setTextColor(textColor)
                layoutParams = RelativeLayout.LayoutParams(RelativeLayout.LayoutParams.WRAP_CONTENT, RelativeLayout.LayoutParams.WRAP_CONTENT).apply {
                    addRule(RelativeLayout.ALIGN_PARENT_END)
                    addRule(RelativeLayout.CENTER_VERTICAL)
                }
            }
            row.addView(leftCol)
            row.addView(tvVal)
            return Pair(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(row)
            }, tvVal)
        }

        val (rowAdsStat, tvAdsR) = createBreakdownRow(R.drawable.ic_close, "Ads", String.format(java.util.Locale.US, "%,d", effectiveAds))
        val (rowTrackersStat, tvTrackersR) = createBreakdownRow(R.drawable.ic_private, "Trackers & Other", String.format(java.util.Locale.US, "%,d", effectiveTrackers))
        val (rowCdnStat, tvCdnR) = createBreakdownRow(R.drawable.ic_download, "CDN / Third-Party", String.format(java.util.Locale.US, "%,d", rawStats.thirdPartyBlocked))
        val (rowDataStat, tvDataR) = createBreakdownRow(R.drawable.ic_history, "Data Saved", HazePrivacyShieldEngine.formatDataSaved(rawStats.dataSavedBytes))

        tvAdsRowVal = tvAdsR
        tvTrackersRowVal = tvTrackersR
        tvCdnRowVal = tvCdnR
        tvDataRowVal = tvDataR

        statisticsContainer.addView(rowAdsStat)
        statisticsContainer.addView(rowTrackersStat)
        statisticsContainer.addView(rowCdnStat)
        statisticsContainer.addView(rowDataStat)

        content.addView(actionsContainer)
        content.addView(statisticsContainer)

        // Segmented Control Tab Switcher Logic
        val selectedBg = GradientDrawable().apply {
            cornerRadius = dpToPx(activity, 10).toFloat()
            setColor(if (isDark) Color.parseColor("#383838") else Color.WHITE)
        }

        btnActions.setOnClickListener {
            activity.triggerStrongHaptic()
            btnActions.background = selectedBg
            btnActions.setTypeface(null, Typeface.BOLD)
            btnActions.setTextColor(textColor)

            btnStatistics.background = null
            btnStatistics.setTypeface(null, Typeface.NORMAL)
            btnStatistics.setTextColor(subTextColor)

            actionsContainer.visibility = View.VISIBLE
            statisticsContainer.visibility = View.GONE
        }

        btnStatistics.setOnClickListener {
            activity.triggerStrongHaptic()
            btnStatistics.background = selectedBg
            btnStatistics.setTypeface(null, Typeface.BOLD)
            btnStatistics.setTextColor(textColor)

            btnActions.background = null
            btnActions.setTypeface(null, Typeface.NORMAL)
            btnActions.setTextColor(subTextColor)

            actionsContainer.visibility = View.GONE
            statisticsContainer.visibility = View.VISIBLE
        }

        // Master switch toggle handler
        masterSwitch.setOnCheckedChangeListener { _, isChecked ->
            activity.triggerStrongHaptic()
            HazePrivacyShieldEngine.setShieldEnabled(activity, isChecked)
            tvSub.text = if (isChecked) "Protection Active" else "Protection Disabled"
            tvSub.setTextColor(if (isChecked) accentColor else subTextColor)
            activity.updatePrivacyShieldButton()
        }

        // --- 6. REAL-TIME LIVE UPDATE LISTENER ---
        val shieldListener = object : HazePrivacyShieldEngine.ShieldListener {
            override fun onShieldEvent(category: String, totalBlocked: Long) {
                activity.runOnUiThread {
                    val currentStats = HazePrivacyShieldEngine.getSessionStats()
                    val currentTotal = HazePrivacyShieldEngine.getTotalBlocked(activity)

                    val curAds = currentStats.adsBlocked + currentStats.unwantedScriptsBlocked
                    val curTrackers = currentStats.trackersBlocked + currentStats.thirdPartyBlocked + currentStats.maliciousBlocked + currentStats.phishingBlocked
                    val curTotal = maxOf(currentTotal, curAds + curTrackers)

                    tvAdsVal.text = String.format(java.util.Locale.US, "%,d", curAds)
                    tvTrackersVal.text = String.format(java.util.Locale.US, "%,d", curTrackers)
                    tvTotalVal.text = String.format(java.util.Locale.US, "%,d", curTotal)

                    tvAdsRowVal.text = String.format(java.util.Locale.US, "%,d", curAds)
                    tvTrackersRowVal.text = String.format(java.util.Locale.US, "%,d", curTrackers)
                    tvCdnRowVal.text = String.format(java.util.Locale.US, "%,d", currentStats.thirdPartyBlocked)
                    tvDataRowVal.text = HazePrivacyShieldEngine.formatDataSaved(currentStats.dataSavedBytes)

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
                Toast.makeText(activity, "Shield counters reset", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
                activity.updatePrivacyShieldButton()
            }
        }
        content.addView(btnReset)

        dialog.setContentView(root)
        dialog.show()
    }
}
