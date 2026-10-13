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
    private val sessionTotalBlocked = AtomicLong(0)
    private val sessionDataSavedBytes = AtomicLong(0)
    private val sessionTimeSavedMs = AtomicLong(0)
    private val cachedAllTimeTotal = AtomicLong(0)

    private val whitelistedDomainsCache = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

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
        val headers = mapOf(
            "Access-Control-Allow-Origin" to "*",
            "Cache-Control" to "no-cache, no-store, must-revalidate",
            "X-Haze-Blocked" to "true"
        )
        val lower = requestUrl.lowercase()
        return when {
            lower.contains(".png") || lower.contains(".jpg") || lower.contains(".jpeg") ||
            lower.contains(".webp") || lower.contains(".gif") || lower.contains("/ad.png") ||
            lower.contains("creative") || lower.contains("banner") -> {
                WebResourceResponse("image/png", "UTF-8", 403, "Blocked", headers, ByteArrayInputStream(TRANSPARENT_1X1_PNG))
            }
            lower.contains(".js") || lower.contains("/js") || lower.contains("analytics") || lower.contains("track") -> {
                WebResourceResponse("application/javascript", "UTF-8", 403, "Blocked", headers, ByteArrayInputStream(ByteArray(0)))
            }
            lower.contains(".css") -> {
                WebResourceResponse("text/css", "UTF-8", 403, "Blocked", headers, ByteArrayInputStream(ByteArray(0)))
            }
            else -> {
                WebResourceResponse("text/plain", "UTF-8", 403, "Blocked", headers, ByteArrayInputStream(ByteArray(0)))
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

    private fun notifyListeners(category: String, total: Long) {
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

    fun isShieldEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences("HazePrefs", Context.MODE_PRIVATE)
        return prefs.getBoolean(PREF_SHIELD_GLOBAL_ENABLED, true)
    }

    fun setShieldEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences("HazePrefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean(PREF_SHIELD_GLOBAL_ENABLED, enabled).apply()
        notifyListeners("CONFIG_CHANGE", getTotalBlocked(context))
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

        val isYouTubePage = isYouTubeHost(pageHost)
        if (isYouTubePage) {
            val prefs = context.getSharedPreferences("HazePrefs", Context.MODE_PRIVATE)
            val blockVideoAds = prefs.getBoolean(PREF_BLOCK_VIDEO_ADS, true)
            // YouTube delivers the actual player through googlevideo.com. Never
            // send those media segments through generic ad/path filters.
            if (isYouTubeMediaRequest(requestUrl)) return BlockDecision.ALLOW

            if (blockVideoAds && isYouTubeAdRequest(reqHost, requestUri.path.orEmpty(), requestUrl)) {
                val decision = BlockDecision(
                    shouldBlock = true,
                    category = CATEGORY_ADS,
                    reason = "YouTube advertising request",
                    bytesSaved = 48_000L,
                    timeSavedMs = 120L,
                    targetUrl = requestUrl
                )
                recordEvent(context, decision, reqHost, requestUrl, "YOUTUBE_AD", isPrivate)
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
                recordEvent(context, decision, reqHost, requestUrl, "NATIVE_C_ENGINE", isPrivate)
                return decision
            }
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
            recordEvent(context, decision, reqHost, requestUrl, "HTML_DOCUMENT", isPrivate)
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
            recordEvent(context, decision, reqHost, requestUrl, if (isMainFrame) "HTML_DOCUMENT" else "SCRIPT", isPrivate)
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
            recordEvent(context, decision, reqHost, requestUrl, "POPUP", isPrivate)
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
            recordEvent(context, decision, reqHost, requestUrl, "SPONSORED_WIDGET", isPrivate)
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
                val decision = BlockDecision(true, category, "Filter rule: ${match.rule}", 0L, 0L, targetUrl = requestUrl)
                recordEvent(context, decision, reqHost, requestUrl, "NETWORK", isPrivate)
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
            recordEvent(context, decision, reqHost, requestUrl, "AD_NETWORK", isPrivate)
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
            recordEvent(context, decision, reqHost, requestUrl, "TRACKING_SCRIPT", isPrivate)
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
            recordEvent(context, decision, reqHost, requestUrl, "UNWANTED_SCRIPT", isPrivate)
            return decision
        }

        // 8. Path Patterns Check (e.g. /pagead/js/adsbygoogle.js, /pixel/, /beacon/, /analytics.js)
        val path = requestUri.path ?: ""
        if (path.isNotEmpty()) {
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
                        recordEvent(context, decision, reqHost, requestUrl, "URL_FILTER", isPrivate)
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
                    recordEvent(context, decision, reqHost, requestUrl, "CROSS_SITE_TRACKER", isPrivate)
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

    internal fun isYouTubeMediaRequest(requestUrl: String): Boolean {
        val uri = try { Uri.parse(requestUrl) } catch (_: Exception) { return false }
        val host = uri.host?.lowercase()?.removePrefix("www.") ?: return false
        val path = uri.path.orEmpty().lowercase()
        return (host == "googlevideo.com" || host.endsWith(".googlevideo.com")) &&
            (path == "/videoplayback" || path.startsWith("/videoplayback/") ||
                path.contains("/manifest/") || uri.getQueryParameter("mime")?.startsWith("video/") == true)
    }

    private fun isYouTubeAdRequest(host: String, path: String, url: String): Boolean {
        val adHosts = host == "doubleclick.net" || host.endsWith(".doubleclick.net") ||
            host == "googleadservices.com" || host.endsWith(".googleadservices.com") ||
            host == "googlesyndication.com" || host.endsWith(".googlesyndication.com") ||
            host == "ads.youtube.com" || host.endsWith(".ads.youtube.com")
        if (adHosts) return true

        val lowerUrl = url.lowercase()
        val lowerPath = path.lowercase()
        return (host == "youtube.com" || host.endsWith(".youtube.com")) &&
            (lowerPath.contains("/api/stats/ads") ||
                lowerPath.contains("/pagead/") ||
                lowerUrl.contains("ad_break") ||
                lowerUrl.contains("adformat="))
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
        isPrivate: Boolean
    ) {
        // 1. Update In-Memory Session Counters
        when (decision.category) {
            CATEGORY_ADS -> sessionAdsBlocked.incrementAndGet()
            CATEGORY_TRACKERS -> sessionTrackersBlocked.incrementAndGet()
            CATEGORY_POPUPS -> sessionPopupsBlocked.incrementAndGet()
            CATEGORY_MALWARE -> sessionMalwareBlocked.incrementAndGet()
            CATEGORY_MALICIOUS -> {
                sessionMaliciousBlocked.incrementAndGet()
                sessionMalwareBlocked.incrementAndGet()
            }
            CATEGORY_PHISHING -> {
                sessionPhishingBlocked.incrementAndGet()
                sessionMalwareBlocked.incrementAndGet()
            }
            CATEGORY_UNWANTED_SCRIPTS -> {
                sessionUnwantedScriptsBlocked.incrementAndGet()
                sessionTrackersBlocked.incrementAndGet()
            }
            CATEGORY_THIRD_PARTY -> {
                sessionThirdPartyBlocked.incrementAndGet()
                sessionTrackersBlocked.incrementAndGet()
            }
        }
        sessionTotalBlocked.incrementAndGet()
        sessionDataSavedBytes.addAndGet(decision.bytesSaved)
        sessionTimeSavedMs.addAndGet(decision.timeSavedMs)

        val total = sessionTotalBlocked.get()
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
        val dbStats = HazeDatabase.getInstance(context).getBlockedTodayStats()
        return BlockedTodayStats(
            ads = dbStats.ads + sessionAdsBlocked.get(),
            trackers = dbStats.trackers + sessionTrackersBlocked.get(),
            popups = dbStats.popups + sessionPopupsBlocked.get(),
            malware = dbStats.malware + sessionMalwareBlocked.get(),
            dataSavedBytes = dbStats.dataSavedBytes + sessionDataSavedBytes.get(),
            timeSavedMs = dbStats.timeSavedMs + sessionTimeSavedMs.get()
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
        sessionTotalBlocked.set(0)
        sessionDataSavedBytes.set(0)
        sessionTimeSavedMs.set(0)

        dbExecutor.execute {
            HazeDatabase.getInstance(context).resetPrivacyStats()
        }
        notifyListeners("RESET", 0)
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
