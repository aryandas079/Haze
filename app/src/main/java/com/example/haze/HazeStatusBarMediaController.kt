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
    private var lastMediaArtist = ""
    private var lastMediaArtworkUrl = ""

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

            if (title.isNotBlank() && title != "undefined") {
                lastMediaTitle = title
            }
            if (artist.isNotBlank() && artist != "undefined") {
                lastMediaArtist = artist
            }
            if (artworkUrl.isNotBlank()) {
                lastMediaArtworkUrl = artworkUrl
            }

            val domain = try {
                val host = Uri.parse(tab.url).host
                if (!host.isNullOrEmpty()) host.removePrefix("www.") else ""
            } catch (_: Exception) { "" }

            val effectiveTitle = when {
                title.isNotBlank() && title != "undefined" -> title
                lastMediaTitle.isNotBlank() -> lastMediaTitle
                tab.title.isNotBlank() && !tab.title.startsWith("http") -> tab.title
                playing -> "Playing Media"
                else -> "Media Paused"
            }

            val effectiveArtist = when {
                artist.isNotBlank() && artist != "undefined" -> artist
                lastMediaArtist.isNotBlank() -> lastMediaArtist
                domain.isNotBlank() -> domain
                else -> "Audio/Video Stream"
            }

            val displaySubtitle = if (domain.isNotEmpty() && !effectiveArtist.contains(domain, ignoreCase = true)) {
                "$effectiveArtist • $domain"
            } else {
                effectiveArtist
            }

            val effectiveArtwork = if (artworkUrl.isNotBlank()) artworkUrl else lastMediaArtworkUrl

            // Update Notification Center player
            HazeMediaNotificationService.updateMediaNotification(
                activity,
                effectiveTitle,
                displaySubtitle,
                effectiveArtwork,
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
        val currentlyPlaying = isPlaying
        val script = """
            (function() {
                try {
                    var isPlaying = $currentlyPlaying;
                    if (window.__hazeActionHandlers) {
                        var h = isPlaying ? (window.__hazeActionHandlers['pause'] || window.__hazeActionHandlers['playpause']) : (window.__hazeActionHandlers['play'] || window.__hazeActionHandlers['playpause']);
                        if (typeof h === 'function') {
                            h();
                            return;
                        }
                    }
                    var ytpBtn = document.querySelector('.ytp-play-button, button.play-pause-button, #play-pause-button, ytmusic-play-button-renderer');
                    if (ytpBtn) {
                        ytpBtn.click();
                        return;
                    }
                    var media = document.querySelectorAll('video, audio');
                    for (var i = 0; i < media.length; i++) {
                        var m = media[i];
                        if (m.readyState > 0 || m.currentTime > 0) {
                            if (m.paused) {
                                m.play().catch(function(){});
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
                    var prevBtn = document.querySelector('button[aria-label*="Previous" i], button[title*="Previous" i], .ytp-prev-button, [data-testid="control-button-skip-back"], .previous-button, .prev-button');
                    if (prevBtn) {
                        prevBtn.click();
                        return;
                    }
                    var media = document.querySelectorAll('video, audio');
                    for (var i = 0; i < media.length; i++) {
                        if (!media[i].paused || media[i].currentTime > 0) {
                            media[i].currentTime = 0;
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
                    var nextBtn = document.querySelector('button[aria-label*="Next" i], button[title*="Next" i], .ytp-next-button, [data-testid="control-button-skip-forward"], .next-button');
                    if (nextBtn) {
                        nextBtn.click();
                        return;
                    }
                } catch(e) {}
            })();
        """.trimIndent()
        tab.browserTab.evaluateJavascript(script, null)
    }

    companion object {
        val MEDIA_DETECTION_SCRIPT = """
            (function() {
                if (window.__hazeMediaTrackInstalled) {
                    if (window.__hazeScanMediaElements) window.__hazeScanMediaElements();
                    return;
                }
                window.__hazeMediaTrackInstalled = true;
                var _lastReportedPlaying = null;
                var _lastReportedTitle = '';
                var _lastReportedArtist = '';
                var _lastReportedArtwork = '';
                var _lastReportedMuted = null;
                var _checkTimer = null;

                function scheduleCheckMedia(delay) {
                    if (_checkTimer) clearTimeout(_checkTimer);
                    _checkTimer = setTimeout(checkMedia, delay || 80);
                }

                function checkMedia() {
                    try {
                        var media = document.querySelectorAll('video, audio');
                        var isPlaying = false;
                        var width = 16;
                        var height = 9;
                        var isMuted = false;
                        var activeEl = null;

                        for (var i = 0; i < media.length; i++) {
                            var m = media[i];
                            if (!m.paused && !m.ended && m.readyState > 1) {
                                isPlaying = true;
                                activeEl = m;
                                isMuted = m.muted || m.volume === 0;
                                if (m.tagName.toLowerCase() === 'video' && m.videoWidth > 0 && m.videoHeight > 0) {
                                    width = m.videoWidth;
                                    height = m.videoHeight;
                                }
                                break;
                            }
                        }

                        // If no playing element found, check if an element is cued or paused
                        if (!activeEl) {
                            for (var j = 0; j < media.length; j++) {
                                var candidate = media[j];
                                if (candidate.currentTime > 0 || candidate.readyState > 0) {
                                    activeEl = candidate;
                                    isMuted = candidate.muted || candidate.volume === 0;
                                    break;
                                }
                            }
                        }

                        if (isPlaying !== _lastReportedPlaying) {
                            _lastReportedPlaying = isPlaying;
                            if (window.Android && window.Android.onMediaStateChange) {
                                window.Android.onMediaStateChange(isPlaying, width, height);
                            }
                        }

                        var title = '';
                        var artist = '';
                        var artwork = '';

                        if (navigator.mediaSession && navigator.mediaSession.metadata) {
                            var meta = navigator.mediaSession.metadata;
                            title = meta.title || '';
                            artist = meta.artist || '';
                            if (meta.artwork && meta.artwork.length > 0) {
                                artwork = meta.artwork[meta.artwork.length - 1].src || '';
                            }
                        }

                        // YouTube Music DOM extractors
                        if (!title) {
                            var ytmTitle = document.querySelector('ytmusic-player-bar .title, ytmusic-player-bar .content-info-wrapper .title');
                            if (ytmTitle) title = (ytmTitle.textContent || '').trim();
                        }
                        if (!artist) {
                            var ytmArtist = document.querySelector('ytmusic-player-bar .byline, ytmusic-player-bar .subtitle');
                            if (ytmArtist) artist = (ytmArtist.textContent || '').trim();
                        }
                        if (!artwork) {
                            var ytmArt = document.querySelector('ytmusic-player-bar .image img, ytmusic-player-bar img.image');
                            if (ytmArt && ytmArt.src) artwork = ytmArt.src;
                        }

                        // YouTube standard DOM extractors
                        if (!title) {
                            var ytTitle = document.querySelector('.slim-video-metadata-title, ytm-slim-video-metadata-section-renderer h2, ytm-media-item .media-item-headline, .ytp-title-link, h1.title, h2.title');
                            if (ytTitle) title = (ytTitle.textContent || '').trim();
                        }
                        if (!artist) {
                            var ytChannel = document.querySelector('.slim-owner-channel-name, ytm-slim-owner-renderer .ytm-channel-name, #channel-name, .ytm-channel-name, .ytp-title-channel-name');
                            if (ytChannel) artist = (ytChannel.textContent || '').trim();
                        }
                        if (!title) {
                            var docTitle = (document.title || '').replace(/\s*-\s*YouTube.*$/i, '').trim();
                            if (docTitle && docTitle.toLowerCase() !== 'youtube') title = docTitle;
                        }
                        if (!artwork) {
                            var poster = activeEl ? (activeEl.poster || '') : '';
                            if (poster) artwork = poster;
                            else {
                                var ogImg = document.querySelector('meta[property="og:image"], link[rel="apple-touch-icon"]');
                                if (ogImg) artwork = ogImg.getAttribute('content') || ogImg.getAttribute('href') || '';
                            }
                        }

                        var effTitle = title || _lastReportedTitle;
                        var effArtist = artist || _lastReportedArtist;
                        var effArtwork = artwork || _lastReportedArtwork;

                        if (effTitle && (isPlaying || _lastReportedPlaying !== null)) {
                            if (effTitle !== _lastReportedTitle || effArtist !== _lastReportedArtist || effArtwork !== _lastReportedArtwork || isMuted !== _lastReportedMuted || isPlaying !== _lastReportedPlaying) {
                                _lastReportedTitle = effTitle;
                                _lastReportedArtist = effArtist;
                                _lastReportedArtwork = effArtwork;
                                _lastReportedMuted = isMuted;
                                if (window.Android && window.Android.onMediaDetailsChange) {
                                    window.Android.onMediaDetailsChange(isPlaying, effTitle, effArtist, effArtwork, isMuted);
                                }
                            }
                        }
                    } catch(e) {}
                }

                function trackElement(el) {
                    if (!el || el.__hazeTracked) return;
                    el.__hazeTracked = true;
                    el.addEventListener('play', function() { scheduleCheckMedia(30); }, { passive: true });
                    el.addEventListener('playing', function() { scheduleCheckMedia(30); }, { passive: true });
                    el.addEventListener('pause', function() { scheduleCheckMedia(150); }, { passive: true });
                    el.addEventListener('ended', function() { scheduleCheckMedia(150); }, { passive: true });
                    el.addEventListener('timeupdate', function() {
                        if (!_lastReportedPlaying && !el.paused && (el.readyState >= 1 || el.currentTime > 0)) {
                            scheduleCheckMedia(50);
                        }
                    }, { passive: true });
                    el.addEventListener('volumechange', function() { scheduleCheckMedia(80); }, { passive: true });
                }

                function scanElements() {
                    var all = document.querySelectorAll('video, audio');
                    for (var i = 0; i < all.length; i++) trackElement(all[i]);
                }
                window.__hazeScanMediaElements = scanElements;
                scanElements();

                try {
                    var obs = new MutationObserver(function() {
                        scanElements();
                    });
                    obs.observe(document.documentElement || document.body, { childList: true, subtree: true });
                } catch(_) {}

                if (navigator.mediaSession) {
                    try {
                        var origMetadata = Object.getOwnPropertyDescriptor(navigator.mediaSession, 'metadata');
                        if (origMetadata && origMetadata.set) {
                            var origSet = origMetadata.set;
                            Object.defineProperty(navigator.mediaSession, 'metadata', {
                                set: function(val) {
                                    origSet.call(this, val);
                                    setTimeout(checkMedia, 50);
                                },
                                get: origMetadata.get,
                                configurable: true
                            });
                        }

                        // Intercept setActionHandler to support direct mediaSession triggers
                        window.__hazeActionHandlers = window.__hazeActionHandlers || {};
                        var origSetAction = navigator.mediaSession.setActionHandler;
                        if (origSetAction) {
                            navigator.mediaSession.setActionHandler = function(action, handler) {
                                window.__hazeActionHandlers[action] = handler;
                                return origSetAction.call(this, action, handler);
                            };
                        }
                    } catch(_) {}
                }

                try {
                    var titleEl = document.querySelector('title');
                    if (titleEl) {
                        new MutationObserver(function() {
                            setTimeout(checkMedia, 150);
                        }).observe(titleEl, { childList: true, characterData: true, subtree: true });
                    }
                } catch(_) {}

                // Hook SPA navigation events common in YouTube Music & Spotify
                window.addEventListener('yt-navigate-finish', function() { setTimeout(checkMedia, 200); }, { passive: true });
                window.addEventListener('popstate', function() { setTimeout(checkMedia, 200); }, { passive: true });
                window.addEventListener('hashchange', function() { setTimeout(checkMedia, 200); }, { passive: true });
                document.addEventListener('DOMContentLoaded', scanElements, { passive: true });
                window.addEventListener('load', scanElements, { passive: true });
            })();
        """.trimIndent()
    }
}

