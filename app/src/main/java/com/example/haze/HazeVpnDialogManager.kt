package com.example.haze

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RelativeLayout
import android.widget.TextView

/**
 * Ultra-clean minimalist dashboard for Haze Secure VPN.
 * Features:
 * - Large circular power button centered in the view
 * - Disconnected by default with clean tap-to-connect state
 * - Smooth connecting pulse and progress animation during handshake
 * - Connected state highlighting active tunnel with swapped IP address
 * - Zero clutter, zero unnecessary detailing
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
        val textColor = if (isDark) Color.WHITE else Color.parseColor("#1A1A1A")
        val subTextColor = if (isDark) Color.parseColor("#9E9E9E") else Color.parseColor("#5F6368")
        val greenColor = Color.parseColor("#34A853")
        val amberColor = Color.parseColor("#FB8C00")
        val redColor = Color.parseColor("#EA4335")

        // State colors for power button
        val disconnectedBtnBg = if (isDark) Color.parseColor("#2A2A2D") else Color.parseColor("#EBEBF0")
        val disconnectedBtnStroke = if (isDark) Color.parseColor("#3D3D42") else Color.parseColor("#D1D1D6")
        val disconnectedIconColor = if (isDark) Color.parseColor("#8E8E93") else Color.parseColor("#71717A")

        val connectingBtnBg = if (isDark) Color.parseColor("#332814") else Color.parseColor("#FFF8E1")
        val connectingBtnStroke = amberColor
        val connectingIconColor = amberColor

        val connectedBtnBg = if (isDark) Color.parseColor("#153322") else Color.parseColor("#E8F5E9")
        val connectedBtnStroke = greenColor
        val connectedIconColor = greenColor

        val dialog = Dialog(activity)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.statusBarColor = bgColor

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = GradientDrawable().apply {
                cornerRadius = dpToPx(activity, 28).toFloat()
                setColor(bgColor)
            }
            layoutParams = ViewGroup.LayoutParams(
                (activity.resources.displayMetrics.widthPixels * 0.88).toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            setPadding(dpToPx(activity, 24), dpToPx(activity, 20), dpToPx(activity, 24), dpToPx(activity, 28))
        }

        // Header (Title + Close Button)
        val header = RelativeLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val titleView = TextView(activity).apply {
            text = "Haze VPN"
            textSize = 18f
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
        header.addView(titleView)

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

        // Center Container for the Large Power Button and Connecting Animation
        val buttonFrame = FrameLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(dpToPx(activity, 144), dpToPx(activity, 144)).apply {
                topMargin = dpToPx(activity, 28)
                bottomMargin = dpToPx(activity, 20)
                gravity = Gravity.CENTER_HORIZONTAL
            }
        }

        // Connecting Progress Ring around the button
        val connectingSpinner = ProgressBar(activity).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(amberColor)
            visibility = if (HazeVpnManager.currentState == HazeVpnManager.VpnState.CONNECTING) View.VISIBLE else View.GONE
            layoutParams = FrameLayout.LayoutParams(dpToPx(activity, 140), dpToPx(activity, 140), Gravity.CENTER)
        }
        buttonFrame.addView(connectingSpinner)

        // Large Circular Power Button
        val powerButton = FrameLayout(activity).apply {
            isClickable = true
            isFocusable = true
            layoutParams = FrameLayout.LayoutParams(dpToPx(activity, 112), dpToPx(activity, 112), Gravity.CENTER)
        }

        val buttonBgDrawable = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            val isConn = HazeVpnManager.currentState == HazeVpnManager.VpnState.CONNECTED
            val isConnecting = HazeVpnManager.currentState == HazeVpnManager.VpnState.CONNECTING
            when {
                isConn -> {
                    setColor(connectedBtnBg)
                    setStroke(dpToPx(activity, 2), connectedBtnStroke)
                }
                isConnecting -> {
                    setColor(connectingBtnBg)
                    setStroke(dpToPx(activity, 2), connectingBtnStroke)
                }
                else -> {
                    setColor(disconnectedBtnBg)
                    setStroke(dpToPx(activity, 2), disconnectedBtnStroke)
                }
            }
        }

        powerButton.background = RippleDrawable(
            ColorStateList.valueOf(Color.parseColor("#33FFFFFF")),
            buttonBgDrawable,
            GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.WHITE) }
        )

        val powerIcon = ImageView(activity).apply {
            setImageResource(R.drawable.ic_power)
            val isConn = HazeVpnManager.currentState == HazeVpnManager.VpnState.CONNECTED
            val isConnecting = HazeVpnManager.currentState == HazeVpnManager.VpnState.CONNECTING
            setColorFilter(
                when {
                    isConn -> connectedIconColor
                    isConnecting -> connectingIconColor
                    else -> disconnectedIconColor
                }
            )
            layoutParams = FrameLayout.LayoutParams(dpToPx(activity, 48), dpToPx(activity, 48), Gravity.CENTER)
        }
        powerButton.addView(powerIcon)
        buttonFrame.addView(powerButton)
        root.addView(buttonFrame)

        // Status Label (e.g. "Disconnected", "Connecting...", "Connected")
        val tvStatus = TextView(activity).apply {
            val isConn = HazeVpnManager.currentState == HazeVpnManager.VpnState.CONNECTED
            val isConnecting = HazeVpnManager.currentState == HazeVpnManager.VpnState.CONNECTING
            val isError = HazeVpnManager.currentState == HazeVpnManager.VpnState.ERROR

            text = when {
                isConn -> "Connected"
                isConnecting -> "Connecting..."
                isError -> "Failed to Connect"
                else -> "Disconnected"
            }
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            setTextColor(
                when {
                    isConn -> greenColor
                    isConnecting -> amberColor
                    isError -> redColor
                    else -> subTextColor
                }
            )
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
        }
        root.addView(tvStatus)

        // Swapped IP Label
        val tvSubtext = TextView(activity).apply {
            val isConn = HazeVpnManager.currentState == HazeVpnManager.VpnState.CONNECTED
            val isConnecting = HazeVpnManager.currentState == HazeVpnManager.VpnState.CONNECTING
            val egressIp = HazeVpnManager.currentEgressIp

            text = when {
                isConn && !egressIp.isNullOrEmpty() -> "Swapped IP: $egressIp"
                isConn -> "Protected & Encrypted"
                isConnecting -> "Establishing secure tunnel..."
                else -> "Tap to connect"
            }
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(if (isConn) greenColor else subTextColor)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dpToPx(activity, 8)
                gravity = Gravity.CENTER_HORIZONTAL
            }
        }
        root.addView(tvSubtext)

        // Community Relay Speed Disclaimer
        val tvDisclaimer = TextView(activity).apply {
            text = "Community Relay: Free encrypted tunnel. Network throughput may fluctuate depending on public exit node traffic."
            textSize = 11.5f
            gravity = Gravity.CENTER
            setTextColor(subTextColor)
            setLineSpacing(dpToPx(activity, 2).toFloat(), 1f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dpToPx(activity, 20)
                marginStart = dpToPx(activity, 12)
                marginEnd = dpToPx(activity, 12)
            }
        }
        root.addView(tvDisclaimer)

        // Pulse Animation for Connecting state
        var pulseAnimator: ObjectAnimator? = null

        fun startPulseAnimation() {
            pulseAnimator?.cancel()
            val scaleX = PropertyValuesHolder.ofFloat(View.SCALE_X, 1.0f, 1.07f, 1.0f)
            val scaleY = PropertyValuesHolder.ofFloat(View.SCALE_Y, 1.0f, 1.07f, 1.0f)
            pulseAnimator = ObjectAnimator.ofPropertyValuesHolder(powerButton, scaleX, scaleY).apply {
                duration = 1100
                repeatCount = ObjectAnimator.INFINITE
                repeatMode = ObjectAnimator.RESTART
                start()
            }
        }

        fun stopPulseAnimation() {
            pulseAnimator?.cancel()
            pulseAnimator = null
            powerButton.scaleX = 1.0f
            powerButton.scaleY = 1.0f
        }

        if (HazeVpnManager.currentState == HazeVpnManager.VpnState.CONNECTING) {
            startPulseAnimation()
        }

        // Power Button Click Handler
        powerButton.setOnClickListener {
            activity.triggerStrongHaptic(it)
            when (HazeVpnManager.currentState) {
                HazeVpnManager.VpnState.CONNECTED -> {
                    tvStatus.text = "Disconnecting..."
                    tvStatus.setTextColor(subTextColor)
                    tvSubtext.text = "Restoring direct traffic..."
                    tvSubtext.setTextColor(subTextColor)
                    stopPulseAnimation()
                    connectingSpinner.visibility = View.GONE
                    buttonBgDrawable.setColor(disconnectedBtnBg)
                    buttonBgDrawable.setStroke(dpToPx(activity, 2), disconnectedBtnStroke)
                    powerIcon.setColorFilter(disconnectedIconColor)
                    HazeVpnManager.disconnect(activity)
                }
                HazeVpnManager.VpnState.CONNECTING -> {
                    // Ignore clicks while actively handshaking
                }
                else -> {
                    tvStatus.text = "Connecting..."
                    tvStatus.setTextColor(amberColor)
                    tvSubtext.text = "Establishing secure tunnel..."
                    tvSubtext.setTextColor(subTextColor)
                    connectingSpinner.visibility = View.VISIBLE
                    startPulseAnimation()
                    buttonBgDrawable.setColor(connectingBtnBg)
                    buttonBgDrawable.setStroke(dpToPx(activity, 2), connectingBtnStroke)
                    powerIcon.setColorFilter(connectingIconColor)
                    HazeVpnManager.connect(activity)
                }
            }
        }

        // Real-time listener for VPN state and IP metrics
        val vpnListener = object : HazeVpnManager.VpnListener {
            override fun onVpnStateChanged(state: HazeVpnManager.VpnState, endpoint: HazeVpnManager.VpnEndpoint?, errorMessage: String?) {
                activity.runOnUiThread {
                    when (state) {
                        HazeVpnManager.VpnState.CONNECTED -> {
                            stopPulseAnimation()
                            connectingSpinner.visibility = View.GONE
                            buttonBgDrawable.setColor(connectedBtnBg)
                            buttonBgDrawable.setStroke(dpToPx(activity, 2), connectedBtnStroke)
                            powerIcon.setColorFilter(connectedIconColor)
                            tvStatus.text = "Connected"
                            tvStatus.setTextColor(greenColor)
                            val ip = HazeVpnManager.currentEgressIp
                            tvSubtext.text = if (!ip.isNullOrEmpty()) "Swapped IP: $ip" else "Protected & Encrypted"
                            tvSubtext.setTextColor(greenColor)
                        }
                        HazeVpnManager.VpnState.CONNECTING -> {
                            startPulseAnimation()
                            connectingSpinner.visibility = View.VISIBLE
                            buttonBgDrawable.setColor(connectingBtnBg)
                            buttonBgDrawable.setStroke(dpToPx(activity, 2), connectingBtnStroke)
                            powerIcon.setColorFilter(connectingIconColor)
                            tvStatus.text = "Connecting..."
                            tvStatus.setTextColor(amberColor)
                            tvSubtext.text = "Establishing secure tunnel..."
                            tvSubtext.setTextColor(subTextColor)
                        }
                        HazeVpnManager.VpnState.DISCONNECTED -> {
                            stopPulseAnimation()
                            connectingSpinner.visibility = View.GONE
                            buttonBgDrawable.setColor(disconnectedBtnBg)
                            buttonBgDrawable.setStroke(dpToPx(activity, 2), disconnectedBtnStroke)
                            powerIcon.setColorFilter(disconnectedIconColor)
                            tvStatus.text = "Disconnected"
                            tvStatus.setTextColor(subTextColor)
                            tvSubtext.text = "Tap to connect"
                            tvSubtext.setTextColor(subTextColor)
                        }
                        HazeVpnManager.VpnState.ERROR -> {
                            stopPulseAnimation()
                            connectingSpinner.visibility = View.GONE
                            buttonBgDrawable.setColor(disconnectedBtnBg)
                            buttonBgDrawable.setStroke(dpToPx(activity, 2), redColor)
                            powerIcon.setColorFilter(redColor)
                            tvStatus.text = "Failed to Connect"
                            tvStatus.setTextColor(redColor)
                            tvSubtext.text = errorMessage ?: "Tap to retry"
                            tvSubtext.setTextColor(redColor)
                        }
                        HazeVpnManager.VpnState.DISCONNECTING -> {
                            tvStatus.text = "Disconnecting..."
                            tvStatus.setTextColor(subTextColor)
                            tvSubtext.text = "Restoring direct traffic..."
                            tvSubtext.setTextColor(subTextColor)
                        }
                    }
                }
            }

            override fun onVpnMetricsUpdated(latencyMs: Long, egressIp: String?, location: String?) {
                activity.runOnUiThread {
                    if (HazeVpnManager.currentState == HazeVpnManager.VpnState.CONNECTED && !egressIp.isNullOrEmpty()) {
                        tvSubtext.text = "Swapped IP: $egressIp"
                        tvSubtext.setTextColor(greenColor)
                    }
                }
            }
        }

        HazeVpnManager.addListener(vpnListener)
        dialog.setOnDismissListener {
            stopPulseAnimation()
            HazeVpnManager.removeListener(vpnListener)
            onDismiss?.invoke()
        }

        dialog.setContentView(root)
        dialog.show()
    }
}
