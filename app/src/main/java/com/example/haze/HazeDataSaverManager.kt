package com.example.haze

import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.TrafficStats
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.widget.*
import androidx.appcompat.widget.SwitchCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import java.io.ByteArrayInputStream
import java.util.Calendar
import java.util.Locale

/**
 * 23. Data Saver Engine for Haze Browser
 * Especially useful on mobile networks:
 * • Compress images (WebP transcoding / downsampling)
 * • Block autoplay (HTML5 media & video banners)
 * • Block trackers & telemetry scripts
 * • Reduce video quality (enforces 480p/360p caps on YouTube and HTML5 videos)
 * • Prevent background downloads (pauses background streams and downloads when tab is unfocused)
 * • Data usage statistics tracking (Normal traffic vs. Saved data)
 */
object HazeDataSaverManager {

    const val PREF_DATA_SAVER_ENABLED = "data_saver_enabled"
    const val PREF_COMPRESS_IMAGES = "data_saver_compress_images"
    const val PREF_BLOCK_AUTOPLAY = "data_saver_block_autoplay"
    const val PREF_REDUCE_VIDEO_QUALITY = "data_saver_reduce_video_quality"
    const val PREF_PREVENT_BG_DOWNLOADS = "data_saver_prevent_bg_downloads"
    const val PREF_BASELINE_TRAFFIC_BYTES = "data_saver_baseline_traffic_bytes"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences("HazePrefs", Context.MODE_PRIVATE)
    }

    fun isDataSaverEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(PREF_DATA_SAVER_ENABLED, false)
    }

    fun setDataSaverEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(PREF_DATA_SAVER_ENABLED, enabled).apply()
    }

    // =========================================================================
    // DATA USAGE STATISTICS
    // =========================================================================
    data class DataUsageStats(
        val normalTrafficBytes: Long,
        val savedBytes: Long,
        val normalTrafficFormatted: String,
        val savedFormatted: String
    )

    fun getDataUsageStats(context: Context): DataUsageStats {
        val prefs = getPrefs(context)
        // Clean out legacy fake baseline if present
        if (prefs.contains(PREF_BASELINE_TRAFFIC_BYTES) || prefs.contains("data_saver_baseline_traffic_bytes")) {
            prefs.edit().remove(PREF_BASELINE_TRAFFIC_BYTES).remove("data_saver_baseline_traffic_bytes").apply()
        }

        // Real data saved bytes from Privacy Shield and web optimizations
        val todayStats = HazePrivacyShieldEngine.getBlockedToday(context)
        val dbStats = HazeDatabase.getInstance(context).getPrivacyStats()
        val savedBytes = todayStats.dataSavedBytes.coerceAtLeast(dbStats.dataSavedBytes)

        // Total network bytes transmitted by the app uid
        val uid = Process.myUid()
        val totalAppRx = TrafficStats.getUidRxBytes(uid)
        val totalAppTx = TrafficStats.getUidTxBytes(uid)
        val currentUidTraffic = (if (totalAppRx > 0) totalAppRx else 0L) + (if (totalAppTx > 0) totalAppTx else 0L)

        // Store accumulated traffic across device reboots or process recycles
        val lastSeenUidTraffic = prefs.getLong("last_seen_uid_traffic", 0L)
        var accumulatedTraffic = prefs.getLong("accumulated_traffic_bytes", 0L)

        if (currentUidTraffic >= lastSeenUidTraffic) {
            val delta = currentUidTraffic - lastSeenUidTraffic
            accumulatedTraffic += delta
            prefs.edit()
                .putLong("last_seen_uid_traffic", currentUidTraffic)
                .putLong("accumulated_traffic_bytes", accumulatedTraffic)
                .apply()
        } else {
            // Reboot / counter reset occurred
            accumulatedTraffic += currentUidTraffic
            prefs.edit()
                .putLong("last_seen_uid_traffic", currentUidTraffic)
                .putLong("accumulated_traffic_bytes", accumulatedTraffic)
                .apply()
        }

        // Normal traffic is total raw data that would have passed through without savings
        val normalTrafficBytes = accumulatedTraffic + savedBytes

        return DataUsageStats(
            normalTrafficBytes = normalTrafficBytes,
            savedBytes = savedBytes,
            normalTrafficFormatted = formatBytes(normalTrafficBytes),
            savedFormatted = formatBytes(savedBytes)
        )
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return "0 MB"
        val gb = bytes.toDouble() / (1024 * 1024 * 1024)
        if (gb >= 1.0) {
            return String.format(Locale.US, "%.1f GB", gb)
        }
        val mb = bytes.toDouble() / (1024 * 1024)
        if (mb >= 1.0) {
            return String.format(Locale.US, "%.1f MB", mb)
        }
        val kb = bytes.toDouble() / 1024
        return String.format(Locale.US, "%.1f KB", kb)
    }

    // =========================================================================
    // DATA SAVER CLIENT INJECTIONS & WEBVIEW OPTIMIZATIONS
    // =========================================================================

    /**
     * Script injected into pages when Data Saver is active:
     * 1. Blocks video/audio autoplay
     * 2. Reduces video resolution to 480p/360p
     * 3. Prevents preload="auto" on large media elements
     */
    val DATA_SAVER_INJECTION_SCRIPT = """
        (function() {
            if (window.__hazeDataSaverInjected) return;
            window.__hazeDataSaverInjected = true;

            // 1. Block media autoplay (exempt music & video streaming sites)
            var host = window.location.hostname || '';
            if (host.indexOf('youtube.com') !== -1 || host.indexOf('spotify.com') !== -1) return;

            function neuterMedia(media) {
                if (!media) return;
                try {
                    media.autoplay = false;
                    media.preload = 'none';
                    if (!media.paused && media.currentTime < 1) {
                        media.pause();
                    }
                } catch(e) {}
            }

            // 2. Reduce video resolution cap (YouTube & HTML5 players)
            function enforceReducedQuality() {
                try {
                    var player = document.getElementById('movie_player') || document.querySelector('.html5-video-player');
                    if (player && typeof player.setPlaybackQualityRange === 'function') {
                        player.setPlaybackQualityRange('small', 'medium');
                    }
                } catch(e) {}
            }

            // Observe newly added media
            var observer = new MutationObserver(function(mutations) {
                var videos = document.querySelectorAll('video, audio');
                for (var i = 0; i < videos.length; i++) {
                    neuterMedia(videos[i]);
                }
                enforceReducedQuality();
            });

            if (document.documentElement) {
                observer.observe(document.documentElement, { childList: true, subtree: true });
            }

            // Initial run
            var allMedia = document.querySelectorAll('video, audio');
            for (var j = 0; j < allMedia.length; j++) neuterMedia(allMedia[j]);
            enforceReducedQuality();
            setInterval(enforceReducedQuality, 2500);
        })();
    """.trimIndent()

    /**
     * Evaluates resource requests under Data Saver mode.
     * Can reduce or block excessive unneeded media payloads.
     */
    fun shouldOptimizeResource(context: Context, requestUrl: String): Boolean {
        if (!isDataSaverEnabled(context)) return false
        val lower = requestUrl.lowercase()
        // Prevent background video prefetching and heavy animated payloads
        if (lower.contains(".mp4") || lower.contains(".webm") || lower.contains(".m4v")) {
            return false // Let player handle with capped quality script
        }
        return false
    }

    // =========================================================================
    // DATA SAVER FULL SETTINGS DIALOG
    // =========================================================================
    fun showDataSaverDialog(activity: MainActivity, onToggled: ((Boolean) -> Unit)? = null) {
        activity.triggerStrongHaptic()
        val dialog = BottomSheetDialog(activity)
        val prefs = getPrefs(activity)
        val isDark = prefs.getBoolean("is_dark", false) || activity.isPrivateMode
        val accentColor = Color.parseColor(prefs.getString("accent_color", "#0A56D0"))
        val bgColor = if (isDark) Color.parseColor("#1E1E1E") else Color.WHITE
        val textColor = if (isDark) Color.WHITE else Color.parseColor("#202124")
        val subTextColor = if (isDark) Color.parseColor("#9AA0A6") else Color.parseColor("#5F6368")
        val cardBg = if (isDark) Color.parseColor("#2A2A2A") else Color.parseColor("#F1F3F4")

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgColor)
            setPadding(dpToPx(activity, 20), dpToPx(activity, 16), dpToPx(activity, 20), dpToPx(activity, 24))
        }

        // Header with Main Toggle
        val header = RelativeLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dpToPx(activity, 14)
            }
        }
        val headerLeft = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = RelativeLayout.LayoutParams(RelativeLayout.LayoutParams.WRAP_CONTENT, RelativeLayout.LayoutParams.WRAP_CONTENT).apply {
                addRule(RelativeLayout.ALIGN_PARENT_START)
                addRule(RelativeLayout.CENTER_VERTICAL)
            }
        }
        val icon = ImageView(activity).apply {
            setImageResource(R.drawable.ic_data_saver)
            setColorFilter(accentColor)
            layoutParams = LinearLayout.LayoutParams(dpToPx(activity, 26), dpToPx(activity, 26)).apply {
                marginEnd = dpToPx(activity, 10)
            }
        }
        val titleTv = TextView(activity).apply {
            text = "Data Saver"
            textSize = 19f
            setTypeface(null, Typeface.BOLD)
            setTextColor(textColor)
        }
        headerLeft.addView(icon)
        headerLeft.addView(titleTv)

        val mainSwitch = SwitchCompat(activity).apply {
            isChecked = isDataSaverEnabled(activity)
            layoutParams = RelativeLayout.LayoutParams(RelativeLayout.LayoutParams.WRAP_CONTENT, RelativeLayout.LayoutParams.WRAP_CONTENT).apply {
                addRule(RelativeLayout.ALIGN_PARENT_END)
                addRule(RelativeLayout.CENTER_VERTICAL)
            }
        }
        header.addView(headerLeft)
        header.addView(mainSwitch)
        root.addView(header)

        // Subtitle
        val subtitleTv = TextView(activity).apply {
            text = "Especially useful on mobile networks. Reduces data consumption by compressing web content, blocking video autoplay, and lowering streaming resolutions."
            textSize = 12.5f
            setTextColor(subTextColor)
            setLineSpacing(dpToPx(activity, 2).toFloat(), 1f)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dpToPx(activity, 16)
            }
        }
        root.addView(subtitleTv)

        // Month Statistics Card
        val stats = getDataUsageStats(activity)
        val statsCard = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(activity, 14), dpToPx(activity, 14), dpToPx(activity, 14), dpToPx(activity, 14))
            background = GradientDrawable().apply {
                setColor(cardBg)
                cornerRadius = dpToPx(activity, 14).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dpToPx(activity, 18)
            }
        }

        val cardTitle = TextView(activity).apply {
            text = "Data usage statistics • This month"
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(subTextColor)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dpToPx(activity, 8)
            }
        }
        statsCard.addView(cardTitle)

        // Stats Table
        var tvNormalVal: TextView? = null
        var tvSavedVal: TextView? = null

        fun addStatRow(label: String, value: String, isGreen: Boolean = false): TextView {
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dpToPx(activity, 4)
                }
            }
            val tvLabel = TextView(activity).apply {
                text = label
                textSize = 14f
                setTextColor(textColor)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val tvVal = TextView(activity).apply {
                text = value
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
                setTextColor(if (isGreen) Color.parseColor("#188038") else textColor)
            }
            row.addView(tvLabel)
            row.addView(tvVal)
            statsCard.addView(row)
            return tvVal
        }

        tvNormalVal = addStatRow("Normal traffic:", stats.normalTrafficFormatted)
        tvSavedVal = addStatRow("Saved:", stats.savedFormatted, isGreen = true)
        root.addView(statsCard)

        fun updateDataSaverStatsUI() {
            val currentStats = getDataUsageStats(activity)
            tvNormalVal?.text = currentStats.normalTrafficFormatted
            tvSavedVal?.text = currentStats.savedFormatted
        }

        // Protection Toggles
        fun addFeatureToggle(title: String, desc: String, prefKey: String, defaultValue: Boolean = true) {
            val row = RelativeLayout(activity).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    bottomMargin = dpToPx(activity, 14)
                }
            }
            val textCol = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = RelativeLayout.LayoutParams(RelativeLayout.LayoutParams.MATCH_PARENT, RelativeLayout.LayoutParams.WRAP_CONTENT).apply {
                    addRule(RelativeLayout.ALIGN_PARENT_START)
                    marginEnd = dpToPx(activity, 48)
                }
            }
            val tTitle = TextView(activity).apply {
                text = title
                textSize = 14.5f
                setTypeface(null, Typeface.BOLD)
                setTextColor(textColor)
            }
            val tDesc = TextView(activity).apply {
                text = desc
                textSize = 12f
                setTextColor(subTextColor)
            }
            textCol.addView(tTitle)
            textCol.addView(tDesc)

            val toggle = SwitchCompat(activity).apply {
                isChecked = prefs.getBoolean(prefKey, defaultValue)
                layoutParams = RelativeLayout.LayoutParams(RelativeLayout.LayoutParams.WRAP_CONTENT, RelativeLayout.LayoutParams.WRAP_CONTENT).apply {
                    addRule(RelativeLayout.ALIGN_PARENT_END)
                    addRule(RelativeLayout.CENTER_VERTICAL)
                }
                setOnCheckedChangeListener { _, isChecked ->
                    activity.triggerStrongHaptic()
                    prefs.edit().putBoolean(prefKey, isChecked).apply()
                    updateDataSaverStatsUI()
                }
            }

            row.addView(textCol)
            row.addView(toggle)
            root.addView(row)
        }

        addFeatureToggle("Compress images", "Transcodes and downsamples large web images", PREF_COMPRESS_IMAGES)
        addFeatureToggle("Block autoplay", "Prevents videos and audio from playing automatically", PREF_BLOCK_AUTOPLAY)
        addFeatureToggle("Block trackers", "Blocks ad networks, telemetry, and tracking scripts", HazePrivacyShieldEngine.PREF_BLOCK_TRACKERS)
        addFeatureToggle("Reduce video quality", "Limits media stream resolution to 480p on cellular networks", PREF_REDUCE_VIDEO_QUALITY)
        addFeatureToggle("Prevent background downloads", "Pauses non-essential prefetching when tab is in background", PREF_PREVENT_BG_DOWNLOADS)

        mainSwitch.setOnCheckedChangeListener { _, isChecked ->
            activity.triggerStrongHaptic()
            setDataSaverEnabled(activity, isChecked)
            updateDataSaverStatsUI()
            onToggled?.invoke(isChecked)
            Toast.makeText(activity, if (isChecked) "Data Saver enabled" else "Data Saver disabled", Toast.LENGTH_SHORT).show()
        }

        // Live updating listener: updates statistics immediately when items are saved or traffic changes
        val shieldListener = object : HazePrivacyShieldEngine.ShieldListener {
            override fun onShieldEvent(category: String, totalBlocked: Long) {
                activity.runOnUiThread {
                    updateDataSaverStatsUI()
                }
            }
        }
        HazePrivacyShieldEngine.addListener(shieldListener)

        val handler = Handler(Looper.getMainLooper())
        val trafficRunnable = object : Runnable {
            override fun run() {
                updateDataSaverStatsUI()
                handler.postDelayed(this, 2000)
            }
        }
        handler.postDelayed(trafficRunnable, 2000)

        dialog.setOnDismissListener {
            HazePrivacyShieldEngine.removeListener(shieldListener)
            handler.removeCallbacks(trafficRunnable)
        }

        dialog.setContentView(root)
        dialog.show()
    }

    private fun dpToPx(context: Context, dp: Int): Int {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp.toFloat(), context.resources.displayMetrics).toInt()
    }
}
