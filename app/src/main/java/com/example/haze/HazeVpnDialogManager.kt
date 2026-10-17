package com.example.haze

import android.app.Dialog
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
import android.widget.*
import androidx.appcompat.widget.SwitchCompat

/**
 * Modern floating dashboard for Haze Secure VPN & Privacy Tunnel.
 * Provides:
 * - One-tap encrypted in-browser VPN activation
 * - Multi-region location switching (United States, India, Germany, UK, Japan, etc.)
 * - Zero external app requirement (no Orbot or root needed)
 * - Real exit IP and verified city/country display
 * - One-tap node switching and latency diagnostics
 */
object HazeVpnDialogManager {

    private fun dpToPx(context: Context, dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            context.resources.displayMetrics
        ).toInt()
    }

    fun showVpnDialog(activity: MainActivity, onDismiss: (() -> Unit)? = null) {
        val prefs = activity.getSharedPreferences("HazePrefs", Context.MODE_PRIVATE)
        val isDark = prefs.getBoolean("is_dark", false) || activity.isPrivateMode
        val accentColor = Color.parseColor(prefs.getString("accent_color", "#0A56D0") ?: "#0A56D0")

        val bgColor = if (isDark) Color.parseColor("#1C1C1E") else Color.parseColor("#FFFFFF")
        val cardColor = if (isDark) Color.parseColor("#2C2C2E") else Color.parseColor("#F2F4F7")
        val textColor = if (isDark) Color.WHITE else Color.parseColor("#1A1A1A")
        val subTextColor = if (isDark) Color.parseColor("#9E9E9E") else Color.parseColor("#5F6368")
        val greenColor = Color.parseColor("#34A853")
        val redColor = Color.parseColor("#EA4335")
        val amberColor = Color.parseColor("#FB8C00")

        val dialog = Dialog(activity)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.statusBarColor = bgColor

        val scrollView = ScrollView(activity).apply {
            isVerticalScrollBarEnabled = false
            layoutParams = ViewGroup.LayoutParams(
                (activity.resources.displayMetrics.widthPixels * 0.94).toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dpToPx(activity, 24).toFloat()
                setColor(bgColor)
            }
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setPadding(dpToPx(activity, 20), dpToPx(activity, 20), dpToPx(activity, 20), dpToPx(activity, 20))
        }
        scrollView.addView(root)

        // Header
        val header = RelativeLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dpToPx(activity, 12)
            }
        }

        val iconTitleLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.WRAP_CONTENT,
                RelativeLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                addRule(RelativeLayout.ALIGN_PARENT_START)
                addRule(RelativeLayout.CENTER_VERTICAL)
            }
        }

        val shieldIcon = ImageView(activity).apply {
            setImageResource(R.drawable.ic_vpn)
            setColorFilter(accentColor)
            layoutParams = LinearLayout.LayoutParams(dpToPx(activity, 28), dpToPx(activity, 28)).apply {
                marginEnd = dpToPx(activity, 10)
            }
        }
        iconTitleLayout.addView(shieldIcon)

        val titleView = TextView(activity).apply {
            text = "Haze Secure VPN"
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setTextColor(textColor)
        }
        iconTitleLayout.addView(titleView)
        header.addView(iconTitleLayout)

        val btnClose = ImageButton(activity).apply {
            setImageResource(R.drawable.ic_close)
            setColorFilter(subTextColor)
            setBackgroundColor(Color.TRANSPARENT)
            layoutParams = RelativeLayout.LayoutParams(dpToPx(activity, 32), dpToPx(activity, 32)).apply {
                addRule(RelativeLayout.ALIGN_PARENT_END)
                addRule(RelativeLayout.CENTER_VERTICAL)
            }
            setOnClickListener {
                activity.triggerStrongHaptic(it)
                dialog.dismiss()
            }
        }
        header.addView(btnClose)
        root.addView(header)

        // Subtitle
        val descView = TextView(activity).apply {
            text = "Routes in-browser traffic through high-speed encrypted privacy nodes. Masks your real IP and unblocks websites with zero setup or external apps."
            textSize = 13f
            setTextColor(subTextColor)
            setLineSpacing(dpToPx(activity, 2).toFloat(), 1f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dpToPx(activity, 16)
            }
        }
        root.addView(descView)

        // Status Card
        val statusCard = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dpToPx(activity, 16).toFloat()
                setColor(cardColor)
            }
            setPadding(dpToPx(activity, 16), dpToPx(activity, 14), dpToPx(activity, 16), dpToPx(activity, 14))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dpToPx(activity, 14)
            }
        }

        val switchRow = RelativeLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val tvStatusTitle = TextView(activity).apply {
            id = View.generateViewId()
            text = "VPN Protection"
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(textColor)
            layoutParams = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.WRAP_CONTENT,
                RelativeLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                addRule(RelativeLayout.ALIGN_PARENT_START)
                addRule(RelativeLayout.CENTER_VERTICAL)
            }
        }
        switchRow.addView(tvStatusTitle)

        val vpnSwitch = SwitchCompat(activity).apply {
            id = View.generateViewId()
            isChecked = HazeVpnManager.isVpnEnabled(activity)
            thumbTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf(-android.R.attr.state_checked)),
                intArrayOf(accentColor, Color.parseColor("#BDBDBD"))
            )
            trackTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf(-android.R.attr.state_checked)),
                intArrayOf(accentColor and 0x55FFFFFF, Color.parseColor("#E0E0E0"))
            )
            layoutParams = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.WRAP_CONTENT,
                RelativeLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                addRule(RelativeLayout.ALIGN_PARENT_END)
                addRule(RelativeLayout.CENTER_VERTICAL)
            }
        }
        switchRow.addView(vpnSwitch)
        statusCard.addView(switchRow)

        // Status Dot + Label + Ping
        val detailsRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dpToPx(activity, 10)
            }
        }

        val statusDot = View(activity).apply {
            layoutParams = LinearLayout.LayoutParams(dpToPx(activity, 10), dpToPx(activity, 10)).apply {
                marginEnd = dpToPx(activity, 8)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (HazeVpnManager.currentState == HazeVpnManager.VpnState.CONNECTED) greenColor else subTextColor)
            }
        }
        detailsRow.addView(statusDot)

        val tvStatusText = TextView(activity).apply {
            text = when (HazeVpnManager.currentState) {
                HazeVpnManager.VpnState.CONNECTED -> "Connected & Encrypted"
                HazeVpnManager.VpnState.CONNECTING -> "Establishing Secure Tunnel..."
                HazeVpnManager.VpnState.ERROR -> "Connection Failed"
                else -> "Disconnected"
            }
            textSize = 13.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(when (HazeVpnManager.currentState) {
                HazeVpnManager.VpnState.CONNECTED -> greenColor
                HazeVpnManager.VpnState.CONNECTING -> amberColor
                HazeVpnManager.VpnState.ERROR -> redColor
                else -> subTextColor
            })
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        detailsRow.addView(tvStatusText)

        val tvPingText = TextView(activity).apply {
            text = if (HazeVpnManager.currentLatencyMs > 0) "${HazeVpnManager.currentLatencyMs} ms" else ""
            textSize = 12.5f
            setTextColor(subTextColor)
        }
        detailsRow.addView(tvPingText)
        statusCard.addView(detailsRow)

        // Exit IP & Location details
        val tvIpText = TextView(activity).apply {
            val ip = HazeVpnManager.currentEgressIp
            val loc = HazeVpnManager.currentExitLocation ?: "Encrypted Relay"
            text = if (ip != null) "Protected IP: $ip ($loc)" else "Traffic: Direct (Real IP visible)"
            textSize = 12.5f
            setTextColor(subTextColor)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dpToPx(activity, 8)
            }
        }
        statusCard.addView(tvIpText)
        root.addView(statusCard)

        // Region / Server Selector Card
        val regionCard = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dpToPx(activity, 16).toFloat()
                setColor(cardColor)
            }
            setPadding(dpToPx(activity, 16), dpToPx(activity, 14), dpToPx(activity, 16), dpToPx(activity, 14))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dpToPx(activity, 14)
            }
        }

        val tvRegionTitle = TextView(activity).apply {
            text = "Virtual Location / Region"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(textColor)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dpToPx(activity, 4)
            }
        }
        regionCard.addView(tvRegionTitle)

        val tvRegionDesc = TextView(activity).apply {
            text = "Choose your destination country to mask your geographic identity:"
            textSize = 12f
            setTextColor(subTextColor)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dpToPx(activity, 10)
            }
        }
        regionCard.addView(tvRegionDesc)

        // Horizontal scroll container for region chips
        val hScrollView = HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dpToPx(activity, 10)
            }
        }

        val chipContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        hScrollView.addView(chipContainer)

        var selectedRegionCode = HazeVpnManager.getSelectedRegion(activity)
        val chipViews = mutableListOf<TextView>()

        fun updateChipsAppearance() {
            for ((idx, chip) in chipViews.withIndex()) {
                val region = HazeVpnManager.supportedRegions[idx]
                val isSelected = region.code.equals(selectedRegionCode, ignoreCase = true)
                chip.setTextColor(if (isSelected) Color.WHITE else textColor)
                chip.background = GradientDrawable().apply {
                    cornerRadius = dpToPx(activity, 12).toFloat()
                    setColor(if (isSelected) accentColor else (if (isDark) Color.parseColor("#3A3A3C") else Color.parseColor("#E4E7EB")))
                }
            }
        }

        for (region in HazeVpnManager.supportedRegions) {
            val chip = TextView(activity).apply {
                text = region.name
                textSize = 12.5f
                setTypeface(null, Typeface.BOLD)
                setPadding(dpToPx(activity, 12), dpToPx(activity, 7), dpToPx(activity, 12), dpToPx(activity, 7))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    marginEnd = dpToPx(activity, 8)
                }
                setOnClickListener {
                    activity.triggerStrongHaptic(it)
                    if (selectedRegionCode != region.code) {
                        selectedRegionCode = region.code
                        HazeVpnManager.setSelectedRegion(activity, region.code)
                        updateChipsAppearance()

                        if (HazeVpnManager.isVpnEnabled(activity)) {
                            tvStatusText.text = "Switching to ${region.name}..."
                            tvStatusText.setTextColor(amberColor)
                            (statusDot.background as? GradientDrawable)?.setColor(amberColor)
                            HazeVpnManager.connect(activity)
                        }
                    }
                }
            }
            chipViews.add(chip)
            chipContainer.addView(chip)
        }
        updateChipsAppearance()
        regionCard.addView(hScrollView)

        // Server switch / reconnect button
        val btnSwitchServer = Button(activity).apply {
            text = "Switch Server / Reconnect"
            textSize = 12.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(textColor)
            background = GradientDrawable().apply {
                cornerRadius = dpToPx(activity, 10).toFloat()
                setColor(if (isDark) Color.parseColor("#3A3A3C") else Color.parseColor("#E4E7EB"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx(activity, 38)
            )
            setOnClickListener {
                activity.triggerStrongHaptic(it)
                if (HazeVpnManager.isVpnEnabled(activity)) {
                    tvStatusText.text = "Finding fastest server..."
                    tvStatusText.setTextColor(amberColor)
                    (statusDot.background as? GradientDrawable)?.setColor(amberColor)
                    HazeVpnManager.connect(activity)
                } else {
                    vpnSwitch.isChecked = true
                }
            }
        }
        regionCard.addView(btnSwitchServer)
        root.addView(regionCard)

        // Privacy Specs Card
        val specsCard = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = dpToPx(activity, 16).toFloat()
                setColor(cardColor)
            }
            setPadding(dpToPx(activity, 16), dpToPx(activity, 12), dpToPx(activity, 16), dpToPx(activity, 12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dpToPx(activity, 6)
            }
        }

        val tvSpecsTitle = TextView(activity).apply {
            text = "Security & Privacy Protocol"
            textSize = 13.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(textColor)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dpToPx(activity, 6)
            }
        }
        specsCard.addView(tvSpecsTitle)

        val bulletItems = listOf(
            "Browser-Isolated Tunnel: Operates 100% inside Haze Browser without requiring system VPN permissions or external tools.",
            "Chained Tunnel Architecture: Prioritizes verified encrypted privacy nodes with seamless failover so websites never report device offline.",
            "Pre-Flight Cryptographic Check: Every relay node undergoes active socket and latency validation before routing traffic."
        )

        for (item in bulletItems) {
            val tvBullet = TextView(activity).apply {
                text = "- $item"
                textSize = 11.5f
                setTextColor(subTextColor)
                setLineSpacing(dpToPx(activity, 2).toFloat(), 1f)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dpToPx(activity, 4)
                }
            }
            specsCard.addView(tvBullet)
        }
        root.addView(specsCard)

        // Connect/Disconnect Switch Listener
        vpnSwitch.setOnCheckedChangeListener { _, isChecked ->
            activity.triggerStrongHaptic()
            if (isChecked) {
                tvStatusText.text = "Establishing Secure Tunnel..."
                tvStatusText.setTextColor(amberColor)
                (statusDot.background as? GradientDrawable)?.setColor(amberColor)
                HazeVpnManager.connect(activity)
            } else {
                tvStatusText.text = "Disconnecting..."
                tvStatusText.setTextColor(subTextColor)
                (statusDot.background as? GradientDrawable)?.setColor(subTextColor)
                HazeVpnManager.disconnect(activity)
            }
        }

        // Real-time listener
        val vpnListener = object : HazeVpnManager.VpnListener {
            override fun onVpnStateChanged(state: HazeVpnManager.VpnState, endpoint: HazeVpnManager.VpnEndpoint?, errorMessage: String?) {
                activity.runOnUiThread {
                    when (state) {
                        HazeVpnManager.VpnState.CONNECTED -> {
                            vpnSwitch.isChecked = true
                            tvStatusText.text = "Connected & Encrypted (${endpoint?.displayName ?: "Secure Tunnel"})"
                            tvStatusText.setTextColor(greenColor)
                            (statusDot.background as? GradientDrawable)?.setColor(greenColor)
                        }
                        HazeVpnManager.VpnState.CONNECTING -> {
                            vpnSwitch.isChecked = true
                            tvStatusText.text = "Establishing Secure Tunnel..."
                            tvStatusText.setTextColor(amberColor)
                            (statusDot.background as? GradientDrawable)?.setColor(amberColor)
                        }
                        HazeVpnManager.VpnState.DISCONNECTED -> {
                            vpnSwitch.isChecked = false
                            tvStatusText.text = "Disconnected"
                            tvStatusText.setTextColor(subTextColor)
                            tvPingText.text = ""
                            tvIpText.text = "Traffic: Direct (Real IP visible)"
                            (statusDot.background as? GradientDrawable)?.setColor(subTextColor)
                        }
                        HazeVpnManager.VpnState.ERROR -> {
                            vpnSwitch.isChecked = false
                            tvStatusText.text = "Tunnel Failed"
                            tvStatusText.setTextColor(redColor)
                            (statusDot.background as? GradientDrawable)?.setColor(redColor)
                            if (errorMessage != null) {
                                Toast.makeText(activity, errorMessage, Toast.LENGTH_LONG).show()
                            }
                        }
                        HazeVpnManager.VpnState.DISCONNECTING -> {
                            tvStatusText.text = "Disconnecting..."
                        }
                    }
                }
            }

            override fun onVpnMetricsUpdated(latencyMs: Long, egressIp: String?, location: String?) {
                activity.runOnUiThread {
                    if (latencyMs > 0) {
                        tvPingText.text = "$latencyMs ms"
                    }
                    if (egressIp != null) {
                        val locLabel = location ?: "Secure Tunnel"
                        tvIpText.text = "Protected IP: $egressIp ($locLabel)"
                    }
                }
            }
        }

        HazeVpnManager.addListener(vpnListener)
        dialog.setOnDismissListener {
            HazeVpnManager.removeListener(vpnListener)
            onDismiss?.invoke()
        }

        dialog.setContentView(scrollView)
        dialog.show()
    }
}
