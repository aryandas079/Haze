package com.example.haze

import android.net.Uri
import com.example.haze.engine.IBrowserTab

/**
 * Controller for Media Playback across the browser.
 * Relays media metadata, playback state, and user actions (Play/Pause, Skip Next, Skip Previous, Stop)
 * to HazeMediaNotificationService (system notification center / shade player) and the active web tab.
 */
class HazeStatusBarMediaController(
    private val activity: MainActivity
) {
    private var currentPlayingTab: MainActivity.Tab? = null
    private var isPlaying = false
    private var isMuted = false
    private var lastMediaTitle = ""

    init {
        // Connect Notification Center player listener
        HazeMediaNotificationService.listener = object : HazeMediaNotificationService.Companion.MediaActionListener {
            override fun onNotificationPlay() {
                activity.runOnUiThread {
                    if (!isPlaying) togglePlayPause()
                }
            }

            override fun onNotificationPause() {
                activity.runOnUiThread {
                    if (isPlaying) togglePlayPause()
                }
            }

            override fun onNotificationPrev() {
                activity.runOnUiThread { skipPrevious() }
            }

            override fun onNotificationNext() {
                activity.runOnUiThread { skipNext() }
            }

            override fun onNotificationStop() {
                activity.runOnUiThread {
                    val tab = currentPlayingTab
                    if (tab != null && isPlaying) {
                        togglePlayPause()
                    }
                    HazeMediaNotificationService.stopMediaNotification(activity)
                }
            }
        }
    }

    fun onMediaDetailsUpdated(
        tab: MainActivity.Tab?,
        playing: Boolean,
        title: String,
        artist: String,
        artworkUrl: String,
        muted: Boolean
    ) {
        activity.runOnUiThread {
            if (tab == null) {
                if (isPlaying) {
                    isPlaying = false
                    HazeMediaNotificationService.stopMediaNotification(activity)
                }
                return@runOnUiThread
            }

            currentPlayingTab = tab
            isPlaying = playing
            isMuted = muted

            if (playing) {
                pendingStopRunnable?.let { stopHandler.removeCallbacks(it) }
                pendingStopRunnable = null
            }

            if (playing && title.isNotEmpty() && title != lastMediaTitle) {
                lastMediaTitle = title
            }

            if (!playing) {
                // If paused, update notification to show pause state
                val displayTitle = when {
                    title.isNotBlank() && title != "undefined" -> title
                    tab.title.isNotBlank() && !tab.title.startsWith("http") -> tab.title
                    else -> "Media Paused"
                }
                val domain = try {
                    val host = Uri.parse(tab.url).host
                    if (!host.isNullOrEmpty()) host.removePrefix("www.") else ""
                } catch (_: Exception) { "" }
                val displaySubtitle = when {
                    artist.isNotBlank() && artist != "undefined" -> {
                        if (domain.isNotEmpty()) "$artist • $domain" else artist
                    }
                    domain.isNotBlank() -> domain
                    else -> "Audio/Video Stream"
                }

                HazeMediaNotificationService.updateMediaNotification(
                    activity,
                    displayTitle,
                    displaySubtitle,
                    artworkUrl,
                    isPlaying = false,
                    isMuted = muted
                )
                return@runOnUiThread
            }

            // Populate metadata
            val displayTitle = when {
                title.isNotBlank() && title != "undefined" -> title
                tab.title.isNotBlank() && !tab.title.startsWith("http") -> tab.title
                else -> "Playing Media"
            }

            val domain = try {
                val host = Uri.parse(tab.url).host
                if (!host.isNullOrEmpty()) host.removePrefix("www.") else ""
            } catch (_: Exception) { "" }

            val displaySubtitle = when {
                artist.isNotBlank() && artist != "undefined" -> {
                    if (domain.isNotEmpty()) "$artist • $domain" else artist
                }
                domain.isNotBlank() -> domain
                else -> "Audio/Video Stream"
            }

            // Update Notification Center player
            HazeMediaNotificationService.updateMediaNotification(
                activity,
                displayTitle,
                displaySubtitle,
                artworkUrl,
                playing,
                muted
            )
        }
    }

    private val stopHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var pendingStopRunnable: Runnable? = null

    fun onMediaStopped(tab: MainActivity.Tab?) {
        activity.runOnUiThread {
            if (currentPlayingTab === tab || tab == null) {
                // Debounce stopping: during YouTube Music / web SPA track transitions,
                // the previous audio element ends and the next begins within 300-800ms.
                // Stopping the notification immediately tears down MediaSession and creates audio focus thrash.
                pendingStopRunnable?.let { stopHandler.removeCallbacks(it) }
                val stopTask = Runnable {
                    if (!isPlaying) {
                        currentPlayingTab = null
                        lastMediaTitle = ""
                        HazeMediaNotificationService.stopMediaNotification(activity)
                    }
                }
                pendingStopRunnable = stopTask
                stopHandler.postDelayed(stopTask, 1200L)
            }
        }
    }

    private fun togglePlayPause() {
        val tab = currentPlayingTab ?: return
        val script = """
            (function() {
                try {
                    if (window.__hazeActionHandlers) {
                        var h = isPlaying ? (window.__hazeActionHandlers['pause'] || window.__hazeActionHandlers['playpause']) : (window.__hazeActionHandlers['play'] || window.__hazeActionHandlers['playpause']);
                        if (typeof h === 'function') {
                            h();
                            return;
                        }
                    }
                    var ytpBtn = document.querySelector('.ytp-play-button, button.play-pause-button');
                    if (ytpBtn) {
                        ytpBtn.click();
                        return;
                    }
                    var media = document.querySelectorAll('video, audio');
                    for (var i = 0; i < media.length; i++) {
                        var m = media[i];
                        if (m.readyState > 0 || m.currentTime > 0) {
                            if (m.paused) {
                                m.play();
                            } else {
                                m.pause();
                            }
                            return;
                        }
                    }
                } catch(e) {}
            })();
        """.trimIndent()
        tab.browserTab.evaluateJavascript(script, null)
    }

    private fun skipPrevious() {
        val tab = currentPlayingTab ?: return
        val script = """
            (function() {
                try {
                    if (window.__hazeActionHandlers && typeof window.__hazeActionHandlers['previoustrack'] === 'function') {
                        window.__hazeActionHandlers['previoustrack']();
                        return;
                    }
                    var prevBtn = document.querySelector('button[aria-label="Previous"], button[title*="Previous"], .ytp-prev-button, [data-testid="control-button-skip-back"], .previous-button');
                    if (prevBtn) {
                        prevBtn.click();
                        return;
                    }
                    var media = document.querySelectorAll('video, audio');
                    for (var i = 0; i < media.length; i++) {
                        if (!media[i].paused || media[i].currentTime > 0) {
                            media[i].currentTime = Math.max(0, media[i].currentTime - 10);
                            return;
                        }
                    }
                } catch(e) {}
            })();
        """.trimIndent()
        tab.browserTab.evaluateJavascript(script, null)
    }

    private fun skipNext() {
        val tab = currentPlayingTab ?: return
        val script = """
            (function() {
                try {
                    if (window.__hazeActionHandlers && typeof window.__hazeActionHandlers['nexttrack'] === 'function') {
                        window.__hazeActionHandlers['nexttrack']();
                        return;
                    }
                    var nextBtn = document.querySelector('button[aria-label="Next"], button[title*="Next"], .ytp-next-button, [data-testid="control-button-skip-forward"], .next-button');
                    if (nextBtn) {
                        nextBtn.click();
                        return;
                    }
                    var media = document.querySelectorAll('video, audio');
                    for (var i = 0; i < media.length; i++) {
                        if (!media[i].paused || media[i].currentTime > 0) {
                            media[i].currentTime = Math.min(media[i].duration || 0, media[i].currentTime + 10);
                            return;
                        }
                    }
                } catch(e) {}
            })();
        """.trimIndent()
        tab.browserTab.evaluateJavascript(script, null)
    }
}
