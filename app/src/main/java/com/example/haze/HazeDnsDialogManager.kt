package com.example.haze

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.core.widget.NestedScrollView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog

object HazeDnsDialogManager {

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

    fun showDnsDialog(activity: MainActivity, onSettingsChanged: (() -> Unit)? = null) {
        val prefs = activity.getSharedPreferences(HazeDnsManager.PREFS_NAME, Context.MODE_PRIVATE)
        val isDark = prefs.getBoolean("is_dark", false) || activity.isPrivateMode
        val accentColorHex = prefs.getString("accent_color", "#0A56D0") ?: "#0A56D0"
        val accentColor = Color.parseColor(accentColorHex)
        val alphaAccentColor = Color.argb(32, Color.red(accentColor), Color.green(accentColor), Color.blue(accentColor))

        val bgColor = if (isDark) Color.parseColor("#1C1C1C") else Color.WHITE
        val cardColor = if (isDark) Color.parseColor("#262626") else Color.parseColor("#F4F6F8")
        val cardBorderColor = if (isDark) Color.parseColor("#383838") else Color.parseColor("#E0E4E8")
        val textColor = if (isDark) Color.WHITE else Color.parseColor("#202124")
        val subTextColor = if (isDark) Color.parseColor("#9AA0A6") else Color.parseColor("#5F6368")
        val successColor = Color.parseColor("#22C55E")

        var currentMode = HazeDnsManager.getDnsMode(activity)
        var currentProtocol = HazeDnsManager.getDnsProtocol(activity)
        var malwareEnabled = HazeDnsManager.isMalwareBlockingEnabled(activity)
        var trackerEnabled = HazeDnsManager.isTrackerBlockingEnabled(activity)

        val dialog = BottomSheetDialog(activity)
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.TRANSPARENT)
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadii = floatArrayOf(
                    dpToPx(activity, 24).toFloat(), dpToPx(activity, 24).toFloat(),
                    dpToPx(activity, 24).toFloat(), dpToPx(activity, 24).toFloat(),
                    0f, 0f, 0f, 0f
                )
                setColor(bgColor)
            }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            setPadding(dpToPx(activity, 20), dpToPx(activity, 12), dpToPx(activity, 20), dpToPx(activity, 24))
        }
        root.addView(container)

        // Handle pill
        val handlePill = View(activity).apply {
            layoutParams = LinearLayout.LayoutParams(dpToPx(activity, 36), dpToPx(activity, 4)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dpToPx(activity, 14)
            }
            background = GradientDrawable().apply {
                cornerRadius = dpToPx(activity, 2).toFloat()
                setColor(if (isDark) Color.parseColor("#444444") else Color.parseColor("#D0D0D0"))
            }
        }
        container.addView(handlePill)

        // Header: Icon + Title + Subtitle
        val headerLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dpToPx(activity, 16)
            }
        }

        val iconDns = ImageView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(dpToPx(activity, 38), dpToPx(activity, 38)).apply {
                marginEnd = dpToPx(activity, 14)
            }
            setImageResource(R.drawable.ic_dns)
            setColorFilter(accentColor)
            background = GradientDrawable().apply {
                cornerRadius = dpToPx(activity, 12).toFloat()
                setColor(alphaAccentColor)
            }
            setPadding(dpToPx(activity, 7), dpToPx(activity, 7), dpToPx(activity, 7), dpToPx(activity, 7))
        }
        headerLayout.addView(iconDns)

        val headerTextCol = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val tvTitle = TextView(activity).apply {
            text = "DNS Protection"
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setTextColor(textColor)
        }
        val tvSubtitle = TextView(activity).apply {
            text = "Encrypt lookups with DoH & DoT, block malware & trackers"
            textSize = 12f
            setTextColor(subTextColor)
            setLineSpacing(dpToPx(activity, 1).toFloat(), 1f)
        }
        headerTextCol.addView(tvTitle)
        headerTextCol.addView(tvSubtitle)
        headerLayout.addView(headerTextCol)
        container.addView(headerLayout)

        val scrollContent = NestedScrollView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(activity, 480))
            isVerticalScrollBarEnabled = false
        }
        val innerContent = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        scrollContent.addView(innerContent)
        container.addView(scrollContent)

        // ------------------ SECTION 1: DNS PROVIDERS ------------------
        val providersCard = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dpToPx(activity, 16).toFloat()
                setColor(cardColor)
                setStroke(dpToPx(activity, 1), cardBorderColor)
            }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dpToPx(activity, 16)
            }
            setPadding(dpToPx(activity, 14), dpToPx(activity, 8), dpToPx(activity, 14), dpToPx(activity, 8))
        }
        innerContent.addView(providersCard)

        val providerRows = mutableMapOf<String, Pair<RadioButton, TextView>>()
        val customInputContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            visibility = if (currentMode == HazeDnsManager.MODE_CUSTOM) View.VISIBLE else View.GONE
            setPadding(dpToPx(activity, 8), dpToPx(activity, 4), dpToPx(activity, 8), dpToPx(activity, 10))
        }

        fun updateSelectedProvider(mode: String) {
            currentMode = mode
            HazeDnsManager.setDnsMode(activity, mode)
            providerRows.forEach { (m, pair) ->
                val (rb, _) = pair
                rb.isChecked = (m == mode)
            }
            customInputContainer.visibility = if (mode == HazeDnsManager.MODE_CUSTOM) View.VISIBLE else View.GONE
            onSettingsChanged?.invoke()
        }

        val providers = listOf(
            Triple(HazeDnsManager.MODE_AUTOMATIC, "Automatic", "System default DNS with secure fallback"),
            Triple(HazeDnsManager.MODE_CLOUDFLARE, "Cloudflare", "1.1.1.1 — Ultra-fast, privacy-first DNS"),
            Triple(HazeDnsManager.MODE_GOOGLE, "Google", "8.8.8.8 — High-speed global infrastructure"),
            Triple(HazeDnsManager.MODE_QUAD9, "Quad9", "9.9.9.9 — Threat defense & malware blocking"),
            Triple(HazeDnsManager.MODE_CUSTOM, "Custom", "Enter custom DoH endpoint or DoT hostname")
        )

        providers.forEachIndexed { index, (modeKey, title, desc) ->
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                setPadding(0, dpToPx(activity, 10), 0, dpToPx(activity, 10))
                setBackgroundResource(getSelectableRes(activity))
                setOnClickListener {
                    activity.triggerStrongHaptic()
                    updateSelectedProvider(modeKey)
                }
            }

            val rb = RadioButton(activity).apply {
                isChecked = (currentMode == modeKey)
                isClickable = false
                buttonTintList = ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(accentColor, subTextColor)
                )
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = dpToPx(activity, 10)
                }
            }

            val textCol = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val tvPTitle = TextView(activity).apply {
                text = title
                textSize = 15f
                setTypeface(null, if (currentMode == modeKey) Typeface.BOLD else Typeface.NORMAL)
                setTextColor(textColor)
            }
            val tvPDesc = TextView(activity).apply {
                text = desc
                textSize = 11.5f
                setTextColor(subTextColor)
            }
            textCol.addView(tvPTitle)
            textCol.addView(tvPDesc)

            row.addView(rb)
            row.addView(textCol)
            providersCard.addView(row)
            providerRows[modeKey] = Pair(rb, tvPTitle)

            if (index < providers.size - 1) {
                val div = View(activity).apply {
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(activity, 1))
                    setBackgroundColor(if (isDark) Color.parseColor("#333333") else Color.parseColor("#EAEAEA"))
                }
                providersCard.addView(div)
            }
        }

        // Custom URL / Host Input inside providers card
        val etCustomDoh = EditText(activity).apply {
            setText(HazeDnsManager.getCustomDohUrl(activity))
            hint = "e.g. https://dns.adguard-dns.com/dns-query"
            textSize = 13f
            setTextColor(textColor)
            setHintTextColor(subTextColor)
            background = GradientDrawable().apply {
                cornerRadius = dpToPx(activity, 10).toFloat()
                setColor(if (isDark) Color.parseColor("#1E1E1E") else Color.WHITE)
                setStroke(dpToPx(activity, 1), accentColor)
            }
            setPadding(dpToPx(activity, 12), dpToPx(activity, 10), dpToPx(activity, 12), dpToPx(activity, 10))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    val url = s?.toString()?.trim().orEmpty()
                    if (url.isNotBlank()) {
                        HazeDnsManager.setCustomDohUrl(activity, url)
                        // If host is inferred
                        val host = try { android.net.Uri.parse(url).host ?: url } catch (_: Exception) { url }
                        HazeDnsManager.setCustomDotHost(activity, host)
                    }
                }
                override fun afterTextChanged(s: Editable?) {}
            })
        }
        customInputContainer.addView(etCustomDoh)
        providersCard.addView(customInputContainer)

        // ------------------ SECTION 2: PROTOCOL SELECTOR (DoH vs DoT) ------------------
        val tvSectionProto = TextView(activity).apply {
            text = "ENCRYPTION PROTOCOL"
            textSize = 11.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(accentColor)
            setPadding(dpToPx(activity, 4), dpToPx(activity, 4), dpToPx(activity, 4), dpToPx(activity, 6))
        }
        innerContent.addView(tvSectionProto)

        val protoCard = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                cornerRadius = dpToPx(activity, 14).toFloat()
                setColor(if (isDark) Color.parseColor("#262626") else Color.parseColor("#ECEFF1"))
            }
            setPadding(dpToPx(activity, 4), dpToPx(activity, 4), dpToPx(activity, 4), dpToPx(activity, 4))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(activity, 44)).apply {
                bottomMargin = dpToPx(activity, 16)
            }
        }

        val btnDoh = TextView(activity).apply {
            text = "DNS-over-HTTPS (DoH)"
            textSize = 12.5f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
        }
        val btnDot = TextView(activity).apply {
            text = "DNS-over-TLS (DoT)"
            textSize = 12.5f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
        }

        val protoSelectedBg = GradientDrawable().apply {
            cornerRadius = dpToPx(activity, 11).toFloat()
            setColor(if (isDark) Color.parseColor("#383838") else Color.WHITE)
        }

        fun updateProtoUI(proto: String) {
            currentProtocol = proto
            HazeDnsManager.setDnsProtocol(activity, proto)
            if (proto == HazeDnsManager.PROTOCOL_DOH) {
                btnDoh.background = protoSelectedBg
                btnDoh.setTypeface(null, Typeface.BOLD)
                btnDoh.setTextColor(textColor)
                btnDot.background = null
                btnDot.setTypeface(null, Typeface.NORMAL)
                btnDot.setTextColor(subTextColor)
            } else {
                btnDot.background = protoSelectedBg
                btnDot.setTypeface(null, Typeface.BOLD)
                btnDot.setTextColor(textColor)
                btnDoh.background = null
                btnDoh.setTypeface(null, Typeface.NORMAL)
                btnDoh.setTextColor(subTextColor)
            }
            onSettingsChanged?.invoke()
        }

        btnDoh.setOnClickListener {
            activity.triggerStrongHaptic()
            updateProtoUI(HazeDnsManager.PROTOCOL_DOH)
        }
        btnDot.setOnClickListener {
            activity.triggerStrongHaptic()
            updateProtoUI(HazeDnsManager.PROTOCOL_DOT)
        }
        updateProtoUI(currentProtocol)
        protoCard.addView(btnDoh)
        protoCard.addView(btnDot)
        innerContent.addView(protoCard)

        // ------------------ SECTION 3: SECURITY & FILTERING SWITCHES ------------------
        val tvSectionSec = TextView(activity).apply {
            text = "DNS SECURITY & FILTERING"
            textSize = 11.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(accentColor)
            setPadding(dpToPx(activity, 4), dpToPx(activity, 4), dpToPx(activity, 4), dpToPx(activity, 6))
        }
        innerContent.addView(tvSectionSec)

        val secCard = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dpToPx(activity, 16).toFloat()
                setColor(cardColor)
                setStroke(dpToPx(activity, 1), cardBorderColor)
            }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dpToPx(activity, 16)
            }
            setPadding(dpToPx(activity, 14), dpToPx(activity, 6), dpToPx(activity, 14), dpToPx(activity, 6))
        }

        fun createSwitchRow(
            title: String,
            desc: String,
            initialState: Boolean,
            onChanged: (Boolean) -> Unit
        ): LinearLayout {
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                setPadding(0, dpToPx(activity, 10), 0, dpToPx(activity, 10))
            }
            val textCol = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = dpToPx(activity, 12)
                }
            }
            val tvT = TextView(activity).apply {
                text = title
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
                setTextColor(textColor)
            }
            val tvD = TextView(activity).apply {
                text = desc
                textSize = 11.5f
                setTextColor(subTextColor)
            }
            textCol.addView(tvT)
            textCol.addView(tvD)

            val sw = Switch(activity).apply {
                isChecked = initialState
                val thumbColor = ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(accentColor, if (isDark) Color.GRAY else Color.LTGRAY)
                )
                thumbTintList = thumbColor
                setOnCheckedChangeListener { _, checked ->
                    activity.triggerStrongHaptic()
                    onChanged(checked)
                    onSettingsChanged?.invoke()
                }
            }
            row.addView(textCol)
            row.addView(sw)
            return row
        }

        secCard.addView(createSwitchRow(
            "Malware Blocking",
            "Block malicious, phishing, scam and crypto-mining domains directly at DNS level",
            malwareEnabled
        ) { checked ->
            malwareEnabled = checked
            HazeDnsManager.setMalwareBlockingEnabled(activity, checked)
        })

        val secDiv = View(activity).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(activity, 1))
            setBackgroundColor(if (isDark) Color.parseColor("#333333") else Color.parseColor("#EAEAEA"))
        }
        secCard.addView(secDiv)

        secCard.addView(createSwitchRow(
            "Tracker Blocking through DNS",
            "Prevent telemetry, advertising scripts, and tracking pixels from resolving",
            trackerEnabled
        ) { checked ->
            trackerEnabled = checked
            HazeDnsManager.setTrackerBlockingEnabled(activity, checked)
        })
        innerContent.addView(secCard)

        // ------------------ SECTION 4: LIVE DIAGNOSTICS & TEST ------------------
        val testCard = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dpToPx(activity, 16).toFloat()
                setColor(cardColor)
                setStroke(dpToPx(activity, 1), cardBorderColor)
            }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dpToPx(activity, 16)
            }
            setPadding(dpToPx(activity, 14), dpToPx(activity, 12), dpToPx(activity, 14), dpToPx(activity, 12))
        }

        val testHeaderRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        val tvTestStatus = TextView(activity).apply {
            text = "DNS STATUS: ACTIVE"
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(successColor)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        testHeaderRow.addView(tvTestStatus)

        val btnRunTest = TextView(activity).apply {
            text = "Test DNS"
            textSize = 12.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(accentColor)
            background = GradientDrawable().apply {
                cornerRadius = dpToPx(activity, 10).toFloat()
                setColor(alphaAccentColor)
            }
            setPadding(dpToPx(activity, 14), dpToPx(activity, 6), dpToPx(activity, 14), dpToPx(activity, 6))
        }
        testHeaderRow.addView(btnRunTest)
        testCard.addView(testHeaderRow)

        val tvTestDetails = TextView(activity).apply {
            text = "Tap 'Test DNS' to verify secure resolution latency and IP routing."
            textSize = 11.5f
            setTextColor(subTextColor)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dpToPx(activity, 8)
            }
        }
        testCard.addView(tvTestDetails)

        btnRunTest.setOnClickListener {
            activity.triggerStrongHaptic()
            btnRunTest.text = "Testing..."
            tvTestDetails.text = "Sending encrypted DNS query..."
            HazeDnsManager.testDns(activity, "cloudflare.com") { res ->
                btnRunTest.text = "Test DNS"
                if (res.success) {
                    tvTestStatus.text = "DNS STATUS: PROTECTED"
                    tvTestStatus.setTextColor(successColor)
                    val ipStr = res.ips.take(2).joinToString(", ")
                    tvTestDetails.text = "Protocol: ${res.protocol}\nLatency: ${res.latencyMs} ms\nResolved IP: $ipStr\nThreat Defense: Active"
                } else {
                    tvTestStatus.text = "DNS STATUS: UNVERIFIED"
                    tvTestStatus.setTextColor(Color.parseColor("#F59E0B"))
                    tvTestDetails.text = "Error: ${res.error ?: "Unable to connect"}\nLatency: ${res.latencyMs} ms"
                }
            }
        }
        innerContent.addView(testCard)

        // Android System Private DNS link
        val btnSystemDns = TextView(activity).apply {
            text = "Configure Android System Private DNS"
            textSize = 12.5f
            setTextColor(accentColor)
            gravity = Gravity.CENTER
            setPadding(dpToPx(activity, 12), dpToPx(activity, 10), dpToPx(activity, 12), dpToPx(activity, 10))
            setOnClickListener {
                activity.triggerStrongHaptic()
                HazeDnsManager.openSystemPrivateDnsSettings(activity)
            }
        }
        innerContent.addView(btnSystemDns)

        // Apply / Done button
        val btnApply = Button(activity).apply {
            text = "Done"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                cornerRadius = dpToPx(activity, 14).toFloat()
                setColor(accentColor)
            }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(activity, 46)).apply {
                topMargin = dpToPx(activity, 12)
            }
            setOnClickListener {
                activity.triggerStrongHaptic()
                dialog.dismiss()
                onSettingsChanged?.invoke()
            }
        }
        container.addView(btnApply)

        dialog.setContentView(root)
        dialog.show()
    }
}
