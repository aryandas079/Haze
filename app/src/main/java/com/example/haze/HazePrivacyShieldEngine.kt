package com.example.haze

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

object HazePrivacyShieldEngine {

    const val CATEGORY_ADS = "ADS"
    const val CATEGORY_TRACKERS = "TRACKERS"
    const val CATEGORY_POPUPS = "POPUPS"
    const val CATEGORY_MALWARE = "MALWARE"
    const val CATEGORY_MALICIOUS = "MALICIOUS"
    const val CATEGORY_PHISHING = "PHISHING"
    const val CATEGORY_UNWANTED_SCRIPTS = "UNWANTED_SCRIPTS"
    const val CATEGORY_THIRD_PARTY = "THIRD_PARTY"
    const val CATEGORY_TOKENS_CLEANED = "TOKENS_CLEANED"
    const val CATEGORY_BOUNCES_STOPPED = "BOUNCES_STOPPED"

    // Preference Keys
    const val PREF_SHIELD_GLOBAL_ENABLED = "privacy_shield_global_enabled"
    const val PREF_BLOCK_ADS = "privacy_shield_block_ads"
    const val PREF_BLOCK_TRACKERS = "privacy_shield_block_trackers"
    const val PREF_BLOCK_POPUPS = "privacy_shield_block_popups"
    const val PREF_BLOCK_MALWARE = "privacy_shield_block_malware"
    const val PREF_BLOCK_OVERLAYS = "privacy_shield_block_overlays"
    const val PREF_BLOCK_VIDEO_ADS = "privacy_shield_block_video_ads"
    const val PREF_BLOCK_SPONSORED_WIDGETS = "privacy_shield_block_sponsored_widgets"
    const val PREF_BLOCK_UNWANTED_SCRIPTS = "privacy_shield_block_unwanted_scripts"
    const val PREF_BLOCK_THIRD_PARTY = "privacy_shield_block_third_party"
    const val PREF_ENHANCED_PRIVACY = "privacy_shield_enhanced_privacy"

    // In-memory Session Counters
    private val sessionAdsBlocked = AtomicLong(0)
    private val sessionTrackersBlocked = AtomicLong(0)
    private val sessionPopupsBlocked = AtomicLong(0)
    private val sessionMalwareBlocked = AtomicLong(0)
    private val sessionMaliciousBlocked = AtomicLong(0)
    private val sessionPhishingBlocked = AtomicLong(0)
    private val sessionUnwantedScriptsBlocked = AtomicLong(0)
    private val sessionThirdPartyBlocked = AtomicLong(0)
    private val sessionTokensCleaned = AtomicLong(0)
    private val sessionBouncesStopped = AtomicLong(0)
    private val sessionTotalBlocked = AtomicLong(0)
    private val sessionDataSavedBytes = AtomicLong(0)
    private val sessionTimeSavedMs = AtomicLong(0)
    private val cachedAllTimeTotal = AtomicLong(0)

    // In-memory Today Counters (fast, thread-safe, no double counting)
    private val todayAdsBlocked = AtomicLong(0)
    private val todayTrackersBlocked = AtomicLong(0)
    private val todayPopupsBlocked = AtomicLong(0)
    private val todayMalwareBlocked = AtomicLong(0)
    private val todayTokensCleaned = AtomicLong(0)
    private val todayBouncesStopped = AtomicLong(0)
    private val todayDataSavedBytes = AtomicLong(0)
    private val todayTimeSavedMs = AtomicLong(0)
    private val isTodayStatsInitialized = java.util.concurrent.atomic.AtomicBoolean(false)

    private val whitelistedDomainsCache = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    data class BlockedResourceInfo(
        val url: String,
        val domain: String,
        val category: String,
        val reason: String,
        val humanExplanation: String,
        val timestamp: Long = System.currentTimeMillis()
    )

    data class SitePrivacyReport(
        val host: String,
        val fullUrl: String,
        val isHttps: Boolean,
        val trackersBlocked: Long,
        val adsBlocked: Long,
        val cookiesBlocked: Long,
        val fingerprintAttempts: Long,
        val privacyScore: Int,
        val asciiScoreBar: String,
        val isShieldEnabledForSite: Boolean,
        val blockedResources: List<BlockedResourceInfo>
    )

    private class SiteMetrics(val host: String) {
        val trackersBlocked = AtomicLong(0)
        val adsBlocked = AtomicLong(0)
        val cookiesBlocked = AtomicLong(0)
        val fingerprintAttempts = AtomicLong(0)
        val blockedResources = java.util.concurrent.CopyOnWriteArrayList<BlockedResourceInfo>()
    }

    private val siteMetricsMap = java.util.concurrent.ConcurrentHashMap<String, SiteMetrics>()

    init {
        // Seed demo metrics for example.com as specified in signature privacy dashboard requirements
        val exampleMetrics = SiteMetrics("example.com").apply {
            trackersBlocked.set(14)
            adsBlocked.set(31)
            cookiesBlocked.set(8)
            fingerprintAttempts.set(2)
            blockedResources.add(
                BlockedResourceInfo(
                    url = "https://track.example-analytics.com/collect?v=2",
                    domain = "track.example-analytics.com",
                    category = "Tracker",
                    reason = "Third-party behavioral tracking script",
                    humanExplanation = "Blocked an analytics script attempting to profile user navigation and session duration."
                )
            )
            blockedResources.add(
                BlockedResourceInfo(
                    url = "https://ads.doubleclick.net/gampad/ads?iu=banner",
                    domain = "ads.doubleclick.net",
                    category = "Ad",
                    reason = "Display advertising network banner",
                    humanExplanation = "Neutralized an intrusive display advertising banner to save bandwidth and screen real estate."
                )
            )
            blockedResources.add(
                BlockedResourceInfo(
                    url = "https://cdn.adservice.google.com/ads/script.js",
                    domain = "cdn.adservice.google.com",
                    category = "Ad",
                    reason = "Ad delivery network payload",
                    humanExplanation = "Blocked an external ad network script before execution."
                )
            )
            blockedResources.add(
                BlockedResourceInfo(
                    url = "https://tracker.adnxs.com/seg?add=1&t=2",
                    domain = "tracker.adnxs.com",
                    category = "Tracker",
                    reason = "Cross-site retargeting beacon",
                    humanExplanation = "Blocked a retargeting beacon attempting to identify you across advertiser partner websites."
                )
            )
            blockedResources.add(
                BlockedResourceInfo(
                    url = "Third-party cookie on example.com",
                    domain = "example.com",
                    category = "Cookie",
                    reason = "Cross-site advertising cookie isolated",
                    humanExplanation = "Blocked a cross-site tracking cookie from being saved in browser storage."
                )
            )
            blockedResources.add(
                BlockedResourceInfo(
                    url = "HTML5 Canvas Probe",
                    domain = "example.com",
                    category = "Fingerprint",
                    reason = "Stealth canvas fingerprinting attempt",
                    humanExplanation = "Intercepted and spoofed an invisible canvas rendering probe querying GPU hardware characteristics."
                )
            )
        }
        siteMetricsMap["example.com"] = exampleMetrics
    }

    // Bounded LRU Decision Cache (Capacity 1024) for ultra-fast O(1) request evaluation
    private val decisionCache = object : java.util.LinkedHashMap<String, BlockDecision>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, BlockDecision>?): Boolean {
            return size > 1024
        }
    }

    private val TRANSPARENT_1X1_PNG = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D,
        0x49, 0x48, 0x44, 0x52, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
        0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15, 0xC4.toByte(), 0x89.toByte(), 0x00,
        0x00, 0x00, 0x0A, 0x49, 0x44, 0x41, 0x54, 0x78, 0x9C.toByte(), 0x63, 0x00,
        0x01, 0x00, 0x00, 0x05, 0x00, 0x01, 0x0D, 0x0A, 0x2D, 0xB4.toByte(), 0x00,
        0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44, 0xAE.toByte(), 0x42, 0x60, 0x82.toByte()
    )

    fun createBlockedResponse(requestUrl: String): WebResourceResponse {
        val lower = requestUrl.lowercase()
        val headers = mapOf(
            "Access-Control-Allow-Origin" to "*",
            "Cache-Control" to "no-cache, no-store, must-revalidate",
            "X-Haze-Blocked" to "true"
        )
        return when {
            lower.contains(".png") || lower.contains(".jpg") || lower.contains(".jpeg") ||
            lower.contains(".webp") || lower.contains(".gif") || lower.contains("/ad.png") ||
            lower.contains("creative") || lower.contains("banner") -> {
                WebResourceResponse("image/png", "UTF-8", 200, "OK", headers, ByteArrayInputStream(TRANSPARENT_1X1_PNG))
            }
            lower.contains(".js") || lower.contains("/js") || lower.contains("analytics") || lower.contains("track") -> {
                WebResourceResponse("application/javascript", "UTF-8", 200, "OK", headers, ByteArrayInputStream(ByteArray(0)))
            }
            lower.contains(".css") -> {
                WebResourceResponse("text/css", "UTF-8", 200, "OK", headers, ByteArrayInputStream(ByteArray(0)))
            }
            else -> {
                WebResourceResponse("text/plain", "UTF-8", 200, "OK", headers, ByteArrayInputStream(ByteArray(0)))
            }
        }
    }

    // Listeners for UI updates
    interface ShieldListener {
        fun onShieldEvent(category: String, totalBlocked: Long)
    }

    private val listeners = CopyOnWriteArrayList<ShieldListener>()
    private val dbExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun addListener(listener: ShieldListener) {
        if (!listeners.contains(listener)) listeners.add(listener)
    }

    fun init(context: Context) {
        dbExecutor.execute {
            try {
                val rules = HazeFilterListManager.getRules(context)
                val db = HazeDatabase.getInstance(context)
                val domains = db.getWhitelistedDomains()
                whitelistedDomainsCache.clear()
                for (d in domains) {
                    whitelistedDomainsCache.add(d.lowercase().removePrefix("www."))
                }
                val stats = db.getPrivacyStats()
                cachedAllTimeTotal.set(stats.totalBlocked)

                val today = db.getBlockedTodayStats()
                todayAdsBlocked.set(today.ads)
                todayTrackersBlocked.set(today.trackers)
                todayPopupsBlocked.set(today.popups)
                todayMalwareBlocked.set(today.malware)
                todayTokensCleaned.set(today.tokensCleaned)
                todayBouncesStopped.set(today.bouncesStopped)
                todayDataSavedBytes.set(today.dataSavedBytes)
                todayTimeSavedMs.set(today.timeSavedMs)
                isTodayStatsInitialized.set(true)

                if (com.example.haze.native_engine.HazeNativeBridge.isNativeLoaded()) {
                    com.example.haze.native_engine.HazeNativeBridge.nativeClearRules()
                    rules.adDomains.forEach { com.example.haze.native_engine.HazeNativeBridge.nativeAddDomain(it, CATEGORY_ADS) }
                    rules.trackerDomains.forEach { com.example.haze.native_engine.HazeNativeBridge.nativeAddDomain(it, CATEGORY_TRACKERS) }
                    rules.maliciousDomains.forEach { com.example.haze.native_engine.HazeNativeBridge.nativeAddDomain(it, CATEGORY_MALICIOUS) }
                    rules.phishingDomains.forEach { com.example.haze.native_engine.HazeNativeBridge.nativeAddDomain(it, CATEGORY_PHISHING) }
                    rules.pathPatterns.forEach { com.example.haze.native_engine.HazeNativeBridge.nativeAddPatternRule(it, CATEGORY_ADS, false) }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun updateWhitelistCache(domain: String, whitelisted: Boolean) {
        val clean = domain.lowercase().removePrefix("www.")
        if (whitelisted) {
            whitelistedDomainsCache.add(clean)
        } else {
            whitelistedDomainsCache.remove(clean)
        }
        invalidateCache()
    }

    /** Records an Android WebView Safe Browsing verdict; this is an engine verdict, not a UI-only alert. */
    fun recordSafeBrowsingThreat(context: Context, url: String, isPrivate: Boolean, threatType: Int) {
        val host = runCatching { Uri.parse(url).host ?: url }.getOrDefault(url)
        val category = if (threatType == 2) CATEGORY_MALICIOUS else CATEGORY_PHISHING
        recordEvent(context, BlockDecision(true, category, "Android WebView Safe Browsing verdict ($threatType)", targetUrl = url),
            host, url, "MAIN_FRAME", isPrivate)
    }

    fun removeListener(listener: ShieldListener) {
        listeners.remove(listener)
    }

    fun notifyListeners(category: String, total: Long) {
        mainHandler.post {
            for (l in listeners) {
                try { l.onShieldEvent(category, total) } catch (e: Exception) {}
            }
        }
    }

    // Model for evaluation decision
    data class BlockDecision(
        val shouldBlock: Boolean,
        val category: String = "",
        val reason: String = "",
        val bytesSaved: Long = 0L,
        val timeSavedMs: Long = 0L,
        val isHarmfulPageNavigation: Boolean = false,
        val targetUrl: String = ""
    ) {
        companion object {
            val ALLOW = BlockDecision(shouldBlock = false)
        }
    }

    // Essential Infrastructure Domains (Never block to preserve site logins, captchas, standard CDNs)
    private val essentialDomains = hashSetOf(
        "accounts.google.com",
        "recaptcha.net",
        "hcaptcha.com",
        "challenges.cloudflare.com",
        "fonts.googleapis.com",
        "fonts.gstatic.com",
        "cdnjs.cloudflare.com"
    )

    // Known Popup, Popunder, and Redirect Ad Networks
    private val popupNetworks = hashSetOf(
        "popads.net", "popcash.net", "propellerads.com", "adsterra.com", "exoclick.com",
        "hilltopads.net", "clickadu.com", "adcash.com", "popunder.net", "clickunder.ru",
        "poponclick.com", "zeroredirect1.com", "tsyndicate.com"
    )

    // Sponsored Recommendation & Content Discovery Networks
    private val sponsoredWidgetNetworks = hashSetOf(
        "taboola.com", "outbrain.com", "revcontent.com", "mgid.com",
        "nativo.com", "dianomi.com", "content.ad", "adblade.com"
    )

    fun recordPopupBlocked(context: Context, url: String, pageUrl: String?, isPrivate: Boolean) {
        val decision = BlockDecision(
            shouldBlock = true,
            category = CATEGORY_POPUPS,
            reason = "Unsolicited popup / popunder suppressed",
            bytesSaved = 32_000L,
            timeSavedMs = 120L,
            targetUrl = url
        )
        val host = runCatching { Uri.parse(url).host ?: "popup" }.getOrDefault("popup")
        recordEvent(context, decision, host, url, "POPUP", isPrivate)
    }

    fun recordTokenCleaned(context: Context, count: Long = 1) {
        sessionTokensCleaned.addAndGet(count)
        todayTokensCleaned.addAndGet(count)
        sessionTotalBlocked.addAndGet(count)
        todayDataSavedBytes.addAndGet(count * 2048L)
        todayTimeSavedMs.addAndGet(count * 15L)
        val total = getTotalBlocked(context)
        notifyListeners(CATEGORY_TOKENS_CLEANED, total)
        dbExecutor.execute {
            try {
                HazeDatabase.getInstance(context).incrementTokensCleaned(count)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun recordBounceStopped(context: Context) {
        sessionBouncesStopped.incrementAndGet()
        todayBouncesStopped.incrementAndGet()
        sessionTotalBlocked.incrementAndGet()
        todayDataSavedBytes.addAndGet(16_000L)
        todayTimeSavedMs.addAndGet(180L)
        val total = getTotalBlocked(context)
        notifyListeners(CATEGORY_BOUNCES_STOPPED, total)
        dbExecutor.execute {
            try {
                HazeDatabase.getInstance(context).incrementBouncesStopped(1)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun isShieldEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences("HazePrefs", Context.MODE_PRIVATE)
        return prefs.getBoolean(PREF_SHIELD_GLOBAL_ENABLED, true)
    }

    fun setShieldEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences("HazePrefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean(PREF_SHIELD_GLOBAL_ENABLED, enabled).apply()
        invalidateCache()
        notifyListeners("CONFIG_CHANGE", getTotalBlocked(context))
    }

    fun invalidateCache() {
        synchronized(decisionCache) {
            decisionCache.clear()
        }
    }

    fun evaluateRequest(
        context: Context,
        requestUrl: String,
        pageUrl: String?,
        isMainFrame: Boolean,
        isPrivate: Boolean
    ): BlockDecision {
        if (!isShieldEnabled(context)) return BlockDecision.ALLOW
        if (requestUrl.isEmpty() || requestUrl.startsWith("data:") || requestUrl.startsWith("blob:") ||
            requestUrl.startsWith("file:") || requestUrl.startsWith("haze:") || requestUrl.startsWith("about:")) {
            return BlockDecision.ALLOW
        }

        val cacheKey = "$requestUrl|$pageUrl|$isMainFrame"
        synchronized(decisionCache) {
            val cached = decisionCache[cacheKey]
            if (cached != null) return cached
        }

        val decision = evaluateRequestInternal(context, requestUrl, pageUrl, isMainFrame, isPrivate)
        synchronized(decisionCache) {
            decisionCache[cacheKey] = decision
        }
        return decision
    }

    private fun evaluateRequestInternal(
        context: Context,
        requestUrl: String,
        pageUrl: String?,
        isMainFrame: Boolean,
        isPrivate: Boolean
    ): BlockDecision {
        val requestUri = try { Uri.parse(requestUrl) } catch (e: Exception) { return BlockDecision.ALLOW }
        val reqHost = requestUri.host?.lowercase()?.removePrefix("www.") ?: return BlockDecision.ALLOW
        val pageHost = pageUrl?.let {
            try { Uri.parse(it).host?.lowercase()?.removePrefix("www.") } catch (_: Exception) { null }
        }

        // 1. Check user whitelisted domains from in-memory cache
        if (whitelistedDomainsCache.contains(reqHost)) return BlockDecision.ALLOW
        if (pageHost != null && whitelistedDomainsCache.contains(pageHost)) {
            return BlockDecision.ALLOW
        }

        // 2. Check essential services
        if (matchesDomainOrSuffix(reqHost, essentialDomains)) {
            return BlockDecision.ALLOW
        }

        // 2b. CRITICAL: Never block video and audio media streams!
        // YouTube streams chunks from googlevideo.com; other video sites use HLS (.m3u8, .ts), DASH (.mpd, .m4s), MP4/WebM.
        // Blocking media chunks causes video to fail/freeze while only audio plays, or broken video playback.
        if (isMediaStreamRequest(reqHost, requestUri.path.orEmpty(), requestUrl)) {
            return BlockDecision.ALLOW
        }

        val isYouTube = isYouTubeHost(pageHost) || isYouTubeHost(reqHost)
        if (isYouTube) {
            val prefs = context.getSharedPreferences("HazePrefs", Context.MODE_PRIVATE)
            val blockVideoAds = prefs.getBoolean(PREF_BLOCK_VIDEO_ADS, true)

            if (blockVideoAds && isYouTubeAdRequest(reqHost, requestUri.path.orEmpty(), requestUrl)) {
                val decision = BlockDecision(
                    shouldBlock = true,
                    category = CATEGORY_ADS,
                    reason = "YouTube advertising request",
                    bytesSaved = 48_000L,
                    timeSavedMs = 120L,
                    targetUrl = requestUrl
                )
                recordEvent(context, decision, reqHost, requestUrl, "YOUTUBE_AD", isPrivate, pageHost)
                return decision
            }
        }

        // Native C++ HazeShieldCore Execution
        if (com.example.haze.native_engine.HazeNativeBridge.isNativeLoaded()) {
            val nativeDec = com.example.haze.native_engine.HazeNativeBridge.nativeEvaluateRequest(requestUrl, pageUrl, isMainFrame)
            if (nativeDec != null && nativeDec.shouldBlock) {
                val decision = BlockDecision(
                    shouldBlock = true,
                    category = nativeDec.category,
                    reason = nativeDec.reason,
                    bytesSaved = nativeDec.bytesSaved,
                    timeSavedMs = nativeDec.timeSavedMs,
                    isHarmfulPageNavigation = isMainFrame && (nativeDec.category == CATEGORY_PHISHING || nativeDec.category == CATEGORY_MALICIOUS),
                    targetUrl = requestUrl
                )
                recordEvent(context, decision, reqHost, requestUrl, "NATIVE_C_ENGINE", isPrivate, pageHost)
                return decision
            }
        }

        // 2c. Secure DNS Engine Protection (DNS-level Malware & Tracker Defense)
        val dnsDecision = HazeDnsManager.evaluateDnsRequest(context, reqHost, isMainFrame)
        if (dnsDecision != null && dnsDecision.shouldBlock) {
            recordEvent(context, dnsDecision, reqHost, requestUrl, "SECURE_DNS", isPrivate, pageHost)
            return dnsDecision
        }

        val prefs = context.getSharedPreferences("HazePrefs", Context.MODE_PRIVATE)
        val blockAds = prefs.getBoolean(PREF_BLOCK_ADS, true)
        val blockTrackers = prefs.getBoolean(PREF_BLOCK_TRACKERS, true)
        val blockPopups = prefs.getBoolean(PREF_BLOCK_POPUPS, true)
        val blockMalware = prefs.getBoolean(PREF_BLOCK_MALWARE, true)
        val blockSponsoredWidgets = prefs.getBoolean(PREF_BLOCK_SPONSORED_WIDGETS, true)
        val blockUnwanted = prefs.getBoolean(PREF_BLOCK_UNWANTED_SCRIPTS, true)
        val blockThirdParty = prefs.getBoolean(PREF_BLOCK_THIRD_PARTY, true)
        val enhancedPrivacy = prefs.getBoolean(PREF_ENHANCED_PRIVACY, true)

        val rules = HazeFilterListManager.getRules(context)

        // 3. Phishing Check (Crucial for page navigation security)
        if (blockMalware && matchesDomainOrSuffix(reqHost, rules.phishingDomains)) {
            val decision = BlockDecision(
                shouldBlock = true,
                category = CATEGORY_PHISHING,
                reason = "Known deceptive / phishing threat domain",
                bytesSaved = 64_000L,
                timeSavedMs = 250L,
                isHarmfulPageNavigation = isMainFrame,
                targetUrl = requestUrl
            )
            recordEvent(context, decision, reqHost, requestUrl, "HTML_DOCUMENT", isPrivate, pageHost)
            return decision
        }

        // 4. Malware Check
        if (blockMalware && matchesDomainOrSuffix(reqHost, rules.maliciousDomains)) {
            val decision = BlockDecision(
                shouldBlock = true,
                category = CATEGORY_MALICIOUS,
                reason = "Malware distribution & cryptojacking endpoint",
                bytesSaved = 120_000L,
                timeSavedMs = 200L,
                isHarmfulPageNavigation = isMainFrame,
                targetUrl = requestUrl
            )
            recordEvent(context, decision, reqHost, requestUrl, if (isMainFrame) "HTML_DOCUMENT" else "SCRIPT", isPrivate, pageHost)
            return decision
        }

        // 4b. Popup / Popunder Ad Networks Check
        if (blockPopups && matchesDomainOrSuffix(reqHost, popupNetworks)) {
            val decision = BlockDecision(
                shouldBlock = true,
                category = CATEGORY_POPUPS,
                reason = "Known popup / popunder redirect network",
                bytesSaved = 32_000L,
                timeSavedMs = 120L,
                targetUrl = requestUrl
            )
            recordEvent(context, decision, reqHost, requestUrl, "POPUP", isPrivate, pageHost)
            return decision
        }

        // 4c. Sponsored Recommendation Widgets Check
        if (blockSponsoredWidgets && matchesDomainOrSuffix(reqHost, sponsoredWidgetNetworks)) {
            val decision = BlockDecision(
                shouldBlock = true,
                category = CATEGORY_ADS,
                reason = "Sponsored recommendation / content discovery widget",
                bytesSaved = 56_000L,
                timeSavedMs = 140L,
                targetUrl = requestUrl
            )
            recordEvent(context, decision, reqHost, requestUrl, "SPONSORED_WIDGET", isPrivate, pageHost)
            return decision
        }

        // If it's the main frame navigation and not phishing/malware, allow the page document itself to load
        if (isMainFrame) {
            return BlockDecision.ALLOW
        }

        // ABP/uBO-compatible network rules are the primary ad/tracker layer.  A
        // non-null WebResourceResponse from WebViewClient prevents Chromium from
        // opening the matched resource connection at all.
        HazeFilterListManager.getNetworkFilter(context).match(
            HazeContentFilter.Request(requestUrl, pageUrl)
        )?.takeIf { !it.exception }?.let { match ->
            val category = if (match.rule.contains("analytics", true) || match.rule.contains("track", true) ||
                match.rule.contains("privacy", true) || match.rule.contains("pixel", true)) CATEGORY_TRACKERS else CATEGORY_ADS
            if ((category == CATEGORY_ADS && blockAds) || (category == CATEGORY_TRACKERS && blockTrackers)) {
                val bytesSaved = if (category == CATEGORY_ADS) 48_000L else 24_000L
                val timeSaved = if (category == CATEGORY_ADS) 120L else 60L
                val decision = BlockDecision(true, category, "Filter rule: ${match.rule}", bytesSaved, timeSaved, targetUrl = requestUrl)
                recordEvent(context, decision, reqHost, requestUrl, "NETWORK", isPrivate, pageHost)
                return decision
            }
        }

        // 5. Advertisements Check
        if (blockAds && matchesDomainOrSuffix(reqHost, rules.adDomains)) {
            val decision = BlockDecision(
                shouldBlock = true,
                category = CATEGORY_ADS,
                reason = "Advertising creative / ad-serving network",
                bytesSaved = 48_000L, // ~48 KB estimated ad payload
                timeSavedMs = 120L,
                targetUrl = requestUrl
            )
            recordEvent(context, decision, reqHost, requestUrl, "AD_NETWORK", isPrivate, pageHost)
            return decision
        }

        // 6. Trackers & Analytics Check
        if (blockTrackers && matchesDomainOrSuffix(reqHost, rules.trackerDomains)) {
            val decision = BlockDecision(
                shouldBlock = true,
                category = CATEGORY_TRACKERS,
                reason = "Cross-site behavioral tracker & analytics script",
                bytesSaved = 24_000L, // ~24 KB tracker script
                timeSavedMs = 60L,
                targetUrl = requestUrl
            )
            recordEvent(context, decision, reqHost, requestUrl, "TRACKING_SCRIPT", isPrivate, pageHost)
            return decision
        }

        // 7. Unwanted Telemetry / Push Notification Scripts
        if (blockUnwanted && matchesDomainOrSuffix(reqHost, rules.unwantedScriptDomains)) {
            val decision = BlockDecision(
                shouldBlock = true,
                category = CATEGORY_UNWANTED_SCRIPTS,
                reason = "Unwanted telemetry & notification spam beacon",
                bytesSaved = 32_000L,
                timeSavedMs = 90L,
                targetUrl = requestUrl
            )
            recordEvent(context, decision, reqHost, requestUrl, "UNWANTED_SCRIPT", isPrivate, pageHost)
            return decision
        }

        // 8. Path Patterns Check (e.g. /pagead/js/adsbygoogle.js, /pixel/, /beacon/, /analytics.js)
        val path = requestUri.path ?: ""
        if (path.isNotEmpty() && !isMediaStreamRequest(reqHost, path, requestUrl)) {
            for (pattern in rules.pathPatterns) {
                if (requestUrl.contains(pattern, ignoreCase = true)) {
                    val cat = if (pattern.contains("ad", ignoreCase = true)) CATEGORY_ADS else CATEGORY_TRACKERS
                    if ((cat == CATEGORY_ADS && blockAds) || (cat == CATEGORY_TRACKERS && blockTrackers)) {
                        val decision = BlockDecision(
                            shouldBlock = true,
                            category = cat,
                            reason = "Blocked by path rule: $pattern",
                            bytesSaved = 28_000L,
                            timeSavedMs = 70L,
                            targetUrl = requestUrl
                        )
                        recordEvent(context, decision, reqHost, requestUrl, "URL_FILTER", isPrivate, pageHost)
                        return decision
                    }
                }
            }
        }

        // 9. Third-party Tracking Detection (Enhanced Privacy Mode)
        if (blockThirdParty && enhancedPrivacy && !pageUrl.isNullOrEmpty()) {
            if (pageHost != null && !isSameRegisteredDomain(reqHost, pageHost)) {
                if (reqHost.contains("track") || reqHost.contains("pixel") || reqHost.contains("telemetry") ||
                    reqHost.contains("metric") || reqHost.contains("beacon") || path.contains("telemetry")) {
                    val decision = BlockDecision(
                        shouldBlock = true,
                        category = CATEGORY_THIRD_PARTY,
                        reason = "Third-party cross-origin tracking beacon",
                        bytesSaved = 16_000L,
                        timeSavedMs = 50L,
                        targetUrl = requestUrl
                    )
                    recordEvent(context, decision, reqHost, requestUrl, "CROSS_SITE_TRACKER", isPrivate, pageHost)
                    return decision
                }

            }
        }

        return BlockDecision.ALLOW
    }

    internal fun isYouTubeHost(host: String?): Boolean {
        return host == "youtube.com" || host?.endsWith(".youtube.com") == true ||
            host == "youtube-nocookie.com" || host?.endsWith(".youtube-nocookie.com") == true
    }

    internal fun isMediaStreamRequest(host: String, path: String, url: String): Boolean {
        if (host == "googlevideo.com" || host.endsWith(".googlevideo.com")) {
            return true
        }
        if (host.contains("audio-ak-spotify-com") || host.contains("audio-sp-") ||
            (host.contains("scdn.co") && path.lowercase().contains("/mp3-preview/"))) {
            return true
        }
        val lowerPath = path.lowercase()
        if (lowerPath.endsWith(".m3u8") || lowerPath.endsWith(".ts") ||
            lowerPath.endsWith(".mpd") || lowerPath.endsWith(".m4s") ||
            lowerPath.endsWith(".mp4") || lowerPath.endsWith(".webm") ||
            lowerPath.endsWith(".aac") || lowerPath.endsWith(".m4a") ||
            lowerPath.endsWith(".flv") || lowerPath.endsWith(".f4m") ||
            lowerPath.endsWith(".mp3") || lowerPath.endsWith(".ogg") ||
            lowerPath.endsWith(".opus")) {
            return true
        }
        val lowerUrl = url.lowercase()
        if (lowerUrl.contains("videoplayback") ||
            lowerUrl.contains("mime=video/") ||
            lowerUrl.contains("mime=audio/") ||
            lowerUrl.contains("/manifest/hls_variant/") ||
            lowerUrl.contains("/manifest/hls_playlist/")) {
            return true
        }
        return false
    }

    internal fun isYouTubeMediaRequest(requestUrl: String): Boolean {
        val uri = try { Uri.parse(requestUrl) } catch (_: Exception) { return false }
        val host = uri.host?.lowercase()?.removePrefix("www.") ?: return false
        val path = uri.path.orEmpty().lowercase()
        return (host == "googlevideo.com" || host.endsWith(".googlevideo.com")) &&
            (path == "/videoplayback" || path.startsWith("/videoplayback/") ||
                path.contains("/manifest/") || uri.getQueryParameter("mime")?.startsWith("video/") == true)
    }

    private fun isYouTubeAdRequest(host: String, path: String, url: String): Boolean {
        if (host == "googlevideo.com" || host.endsWith(".googlevideo.com")) return false

        val adHosts = host == "doubleclick.net" || host.endsWith(".doubleclick.net") ||
            host == "googleadservices.com" || host.endsWith(".googleadservices.com") ||
            host == "googlesyndication.com" || host.endsWith(".googlesyndication.com") ||
            host == "ads.youtube.com" || host.endsWith(".ads.youtube.com") ||
            host == "adservice.google.com" || host.endsWith(".adservice.google.com")
        if (adHosts) return true

        val lowerPath = path.lowercase()
        val lowerUrl = url.lowercase()
        return (host == "youtube.com" || host.endsWith(".youtube.com")) &&
            (lowerPath.contains("/api/stats/ads") ||
                lowerPath.contains("/pagead/") ||
                lowerPath.contains("/youtubei/v1/player/ad_break") ||
                lowerPath.contains("/ad_break") ||
                lowerUrl.contains("adformat=") ||
                lowerUrl.contains("ad_type="))
    }

    private fun matchesDomainOrSuffix(host: String, domainSet: Set<String>): Boolean {
        if (domainSet.contains(host)) return true

        // Check parent domain suffixes (e.g., "ad.sub.doubleclick.net" checks "sub.doubleclick.net" then "doubleclick.net")
        var current = host
        while (current.contains('.')) {
            current = current.substringAfter('.')
            if (domainSet.contains(current)) {
                return true
            }
        }
        return false
    }

    private fun isSameRegisteredDomain(host1: String, host2: String): Boolean {
        if (host1 == host2) return true
        val p1 = host1.split('.')
        val p2 = host2.split('.')
        if (p1.size >= 2 && p2.size >= 2) {
            val root1 = "${p1[p1.size - 2]}.${p1[p1.size - 1]}"
            val root2 = "${p2[p2.size - 2]}.${p2[p2.size - 1]}"
            return root1 == root2
        }
        return false
    }

    private fun recordEvent(
        context: Context,
        decision: BlockDecision,
        domain: String,
        url: String,
        resourceType: String,
        isPrivate: Boolean,
        pageHost: String? = null
    ) {
        // Record per-site metrics
        if (!pageHost.isNullOrBlank()) {
            val clean = cleanHost(pageHost)
            if (clean.isNotEmpty()) {
                val metrics = siteMetricsMap.computeIfAbsent(clean) { SiteMetrics(it) }
                when (decision.category) {
                    CATEGORY_ADS -> metrics.adsBlocked.incrementAndGet()
                    CATEGORY_TRACKERS, CATEGORY_UNWANTED_SCRIPTS -> metrics.trackersBlocked.incrementAndGet()
                    CATEGORY_POPUPS -> metrics.adsBlocked.incrementAndGet()
                    CATEGORY_THIRD_PARTY -> metrics.cookiesBlocked.incrementAndGet()
                    else -> metrics.trackersBlocked.incrementAndGet()
                }
                val humanExplanation = when (decision.category) {
                    CATEGORY_ADS -> "Advertising server or banner creative blocked to save mobile data and remove clutter."
                    CATEGORY_TRACKERS -> "Cross-site tracking script blocked to stop behavioral profiling and telemetry."
                    CATEGORY_POPUPS -> "Unwanted popup / popunder redirect blocked."
                    CATEGORY_MALWARE, CATEGORY_MALICIOUS -> "Harmful malware or cryptojacking script neutralized."
                    CATEGORY_PHISHING -> "Deceptive phishing threat domain blocked."
                    CATEGORY_THIRD_PARTY -> "Cross-site tracking resource isolated to protect cookie privacy."
                    else -> "Potentially unwanted resource blocked by privacy filter."
                }
                if (metrics.blockedResources.size < 150) {
                    metrics.blockedResources.add(
                        BlockedResourceInfo(
                            url = url,
                            domain = domain,
                            category = decision.category,
                            reason = decision.reason,
                            humanExplanation = humanExplanation
                        )
                    )
                }
            }
        }

        // 1. Update In-Memory Session Counters
        when (decision.category) {
            CATEGORY_ADS -> {
                sessionAdsBlocked.incrementAndGet()
                todayAdsBlocked.incrementAndGet()
            }
            CATEGORY_TRACKERS -> {
                sessionTrackersBlocked.incrementAndGet()
                todayTrackersBlocked.incrementAndGet()
            }
            CATEGORY_POPUPS -> {
                sessionPopupsBlocked.incrementAndGet()
                todayPopupsBlocked.incrementAndGet()
            }
            CATEGORY_MALWARE -> {
                sessionMalwareBlocked.incrementAndGet()
                todayMalwareBlocked.incrementAndGet()
            }
            CATEGORY_MALICIOUS -> {
                sessionMaliciousBlocked.incrementAndGet()
                sessionMalwareBlocked.incrementAndGet()
                todayMalwareBlocked.incrementAndGet()
            }
            CATEGORY_PHISHING -> {
                sessionPhishingBlocked.incrementAndGet()
                sessionMalwareBlocked.incrementAndGet()
                todayMalwareBlocked.incrementAndGet()
            }
            CATEGORY_UNWANTED_SCRIPTS -> {
                sessionUnwantedScriptsBlocked.incrementAndGet()
                sessionTrackersBlocked.incrementAndGet()
                todayTrackersBlocked.incrementAndGet()
            }
            CATEGORY_THIRD_PARTY -> {
                sessionThirdPartyBlocked.incrementAndGet()
                sessionTrackersBlocked.incrementAndGet()
                todayTrackersBlocked.incrementAndGet()
            }
        }
        sessionTotalBlocked.incrementAndGet()
        sessionDataSavedBytes.addAndGet(decision.bytesSaved)
        sessionTimeSavedMs.addAndGet(decision.timeSavedMs)
        todayDataSavedBytes.addAndGet(decision.bytesSaved)
        todayTimeSavedMs.addAndGet(decision.timeSavedMs)

        val total = getTotalBlocked(context)
        notifyListeners(decision.category, total)

        // 2. Strict Privacy Requirement: In private browsing sessions, do NOT record to persistent SQLite database!
        if (isPrivate) return

        // 3. Asynchronously record to SQLite persistent database
        dbExecutor.execute {
            try {
                val db = HazeDatabase.getInstance(context)
                db.incrementPrivacyStats(decision.category, decision.bytesSaved, decision.timeSavedMs)
                val event = BlockedEvent(
                    category = decision.category,
                    domain = domain,
                    url = url,
                    resourceType = resourceType,
                    bytesSaved = decision.bytesSaved,
                    reason = decision.reason,
                    timestamp = System.currentTimeMillis()
                )
                db.recordBlockedEvent(event)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // ==========================================
    // STATS ACCESSORS
    // ==========================================

    fun getSessionStats(): PrivacyShieldStats {
        return PrivacyShieldStats(
            adsBlocked = sessionAdsBlocked.get(),
            trackersBlocked = sessionTrackersBlocked.get(),
            popupsBlocked = sessionPopupsBlocked.get(),
            malwareBlocked = sessionMalwareBlocked.get(),
            maliciousBlocked = sessionMaliciousBlocked.get(),
            phishingBlocked = sessionPhishingBlocked.get(),
            unwantedScriptsBlocked = sessionUnwantedScriptsBlocked.get(),
            thirdPartyBlocked = sessionThirdPartyBlocked.get(),
            totalBlocked = sessionTotalBlocked.get(),
            dataSavedBytes = sessionDataSavedBytes.get(),
            timeSavedMs = sessionTimeSavedMs.get(),
            lastUpdated = System.currentTimeMillis()
        )
    }

    fun getBlockedToday(context: Context): BlockedTodayStats {
        if (!isTodayStatsInitialized.get()) {
            try {
                val db = HazeDatabase.getInstance(context)
                val dbStats = db.getBlockedTodayStats()
                todayAdsBlocked.compareAndSet(0, dbStats.ads)
                todayTrackersBlocked.compareAndSet(0, dbStats.trackers)
                todayPopupsBlocked.compareAndSet(0, dbStats.popups)
                todayMalwareBlocked.compareAndSet(0, dbStats.malware)
                todayTokensCleaned.compareAndSet(0, dbStats.tokensCleaned)
                todayBouncesStopped.compareAndSet(0, dbStats.bouncesStopped)
                todayDataSavedBytes.compareAndSet(0, dbStats.dataSavedBytes)
                todayTimeSavedMs.compareAndSet(0, dbStats.timeSavedMs)
                isTodayStatsInitialized.set(true)
            } catch (_: Exception) {}
        }
        return BlockedTodayStats(
            ads = todayAdsBlocked.get(),
            trackers = todayTrackersBlocked.get(),
            popups = todayPopupsBlocked.get(),
            malware = todayMalwareBlocked.get(),
            tokensCleaned = todayTokensCleaned.get(),
            bouncesStopped = todayBouncesStopped.get(),
            dataSavedBytes = todayDataSavedBytes.get(),
            timeSavedMs = todayTimeSavedMs.get()
        )
    }

    fun getAllTimeStats(context: Context): PrivacyShieldStats {
        return HazeDatabase.getInstance(context).getPrivacyStats()
    }

    fun getTotalBlocked(context: Context): Long {
        // cachedAllTimeTotal is the persisted baseline captured at startup; session
        // events have not yet necessarily reached SQLite, so they must be added.
        return cachedAllTimeTotal.get() + sessionTotalBlocked.get()
    }

    fun resetStats(context: Context) {
        cachedAllTimeTotal.set(0)
        sessionAdsBlocked.set(0)
        sessionTrackersBlocked.set(0)
        sessionPopupsBlocked.set(0)
        sessionMalwareBlocked.set(0)
        sessionMaliciousBlocked.set(0)
        sessionPhishingBlocked.set(0)
        sessionUnwantedScriptsBlocked.set(0)
        sessionThirdPartyBlocked.set(0)
        sessionTokensCleaned.set(0)
        sessionBouncesStopped.set(0)
        sessionTotalBlocked.set(0)
        sessionDataSavedBytes.set(0)
        sessionTimeSavedMs.set(0)

        todayAdsBlocked.set(0)
        todayTrackersBlocked.set(0)
        todayPopupsBlocked.set(0)
        todayMalwareBlocked.set(0)
        todayTokensCleaned.set(0)
        todayBouncesStopped.set(0)
        todayDataSavedBytes.set(0)
        todayTimeSavedMs.set(0)

        siteMetricsMap.clear()
        HazeAntiTrackingEngine.resetSessionStats()

        dbExecutor.execute {
            HazeDatabase.getInstance(context).resetPrivacyStats()
        }
        notifyListeners("RESET", 0)
    }

    // ==========================================
    // PER-SITE PRIVACY METRICS & REPORT
    // ==========================================

    fun cleanHost(hostOrUrl: String): String {
        return HazeSitePermissionManager.cleanHost(hostOrUrl)
    }

    fun recordFingerprintAttemptForHost(context: Context, hostOrUrl: String, probeType: String) {
        val host = cleanHost(hostOrUrl)
        if (host.isEmpty()) return
        val metrics = siteMetricsMap.computeIfAbsent(host) { SiteMetrics(it) }
        metrics.fingerprintAttempts.incrementAndGet()
        sessionTotalBlocked.incrementAndGet()
        val capitalized = probeType.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        if (metrics.blockedResources.size < 150) {
            metrics.blockedResources.add(
                BlockedResourceInfo(
                    url = "$probeType probe on $host",
                    domain = host,
                    category = "Fingerprinting",
                    reason = "$capitalized API probe neutralized",
                    humanExplanation = "Detected an invasive $capitalized fingerprinting probe attempting to extract unique hardware characteristics."
                )
            )
        }
        notifyListeners("Fingerprinting", getTotalBlocked(context))
    }

    fun recordCookieBlockedForHost(hostOrUrl: String) {
        val host = cleanHost(hostOrUrl)
        if (host.isEmpty()) return
        val metrics = siteMetricsMap.computeIfAbsent(host) { SiteMetrics(it) }
        metrics.cookiesBlocked.incrementAndGet()
        if (metrics.blockedResources.size < 150) {
            metrics.blockedResources.add(
                BlockedResourceInfo(
                    url = "Third-party cookie on $host",
                    domain = host,
                    category = "Cookie",
                    reason = "Cross-site tracking cookie isolated",
                    humanExplanation = "Blocked an external cookie attempting to link your session across websites."
                )
            )
        }
    }

    fun getSiteReport(context: Context, currentUrl: String): SitePrivacyReport {
        val host = cleanHost(currentUrl)
        val isHttps = currentUrl.startsWith("https://", ignoreCase = true)
        val metrics = if (host.isNotEmpty()) siteMetricsMap[host] else null

        val trackers = metrics?.trackersBlocked?.get() ?: 0L
        val ads = metrics?.adsBlocked?.get() ?: 0L
        val cookies = metrics?.cookiesBlocked?.get() ?: 0L
        val fingerprints = metrics?.fingerprintAttempts?.get() ?: 0L
        val blockedList = metrics?.blockedResources?.toList()?.reversed() ?: emptyList()

        val camState = HazeSitePermissionManager.getPermission(context, host, HazeSitePermissionManager.PermissionType.CAMERA)
        val locState = HazeSitePermissionManager.getPermission(context, host, HazeSitePermissionManager.PermissionType.LOCATION)

        var score = 100
        if (!isHttps && host.isNotEmpty() && !host.startsWith("localhost") && !host.startsWith("127.0.0.1")) {
            score -= 25
        }
        val trackerPenalty = (trackers * 0.5).toInt().coerceAtMost(25)
        val adPenalty = (ads * 0.2).toInt().coerceAtMost(15)
        val cookiePenalty = (cookies * 0.4).toInt().coerceAtMost(15)
        val fpPenalty = (fingerprints * 1.0).toInt().coerceAtMost(20)

        var permPenalty = 0
        if (camState == HazeSitePermissionManager.State.ALLOWED) permPenalty += 4
        if (locState == HazeSitePermissionManager.State.ALLOWED) permPenalty += 4

        score -= (trackerPenalty + adPenalty + cookiePenalty + fpPenalty + permPenalty)
        val finalScore = score.coerceIn(15, 100)

        val filledBlocks = (finalScore / 10).coerceIn(1, 10)
        val emptyBlocks = 10 - filledBlocks
        val asciiScoreBar = "█".repeat(filledBlocks) + "░".repeat(emptyBlocks)

        val isShieldEnabledForSite = !isHostWhitelisted(host) && isShieldEnabled(context)

        return SitePrivacyReport(
            host = host.ifEmpty { "Current Page" },
            fullUrl = currentUrl,
            isHttps = isHttps,
            trackersBlocked = trackers,
            adsBlocked = ads,
            cookiesBlocked = cookies,
            fingerprintAttempts = fingerprints,
            privacyScore = finalScore,
            asciiScoreBar = asciiScoreBar,
            isShieldEnabledForSite = isShieldEnabledForSite,
            blockedResources = blockedList
        )
    }

    fun isHostWhitelisted(host: String): Boolean {
        val clean = cleanHost(host)
        return whitelistedDomainsCache.contains(clean)
    }

    fun setHostWhitelisted(context: Context, host: String, whitelisted: Boolean) {
        val clean = cleanHost(host)
        if (clean.isEmpty()) return
        updateWhitelistCache(clean, whitelisted)
        dbExecutor.execute {
            try {
                val db = HazeDatabase.getInstance(context)
                db.setDomainWhitelisted(clean, whitelisted)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        notifyListeners("WHITELIST", getTotalBlocked(context))
    }

    fun getBlockedItemsForHost(hostOrUrl: String): List<BlockedResourceInfo> {
        val clean = cleanHost(hostOrUrl)
        if (clean.isEmpty()) return emptyList()
        return siteMetricsMap[clean]?.blockedResources?.toList()?.reversed() ?: emptyList()
    }

    // ==========================================
    // FORMATTING HELPERS
    // ==========================================

    fun formatDataSaved(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0)
            bytes < 1024 * 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
            else -> String.format(java.util.Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
        }
    }

    fun formatTimeSaved(timeMs: Long): String {
        val seconds = timeMs / 1000
        return when {
            seconds < 60 -> "$seconds seconds saved"
            seconds < 3600 -> {
                val mins = seconds / 60
                val remSecs = seconds % 60
                if (remSecs > 0) "$mins min $remSecs sec saved" else "$mins minutes saved"
            }
            seconds < 86400 -> {
                val hours = seconds / 3600
                val remMins = (seconds % 3600) / 60
                if (remMins > 0) "$hours hr $remMins min saved" else "$hours hours saved"
            }
            else -> {
                val days = seconds / 86400
                val remHours = (seconds % 86400) / 3600
                if (remHours > 0) "$days days $remHours hrs saved" else "$days days saved"
            }
        }
    }
}
