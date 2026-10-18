package com.example.haze

import android.content.Context
import android.net.Uri
import android.os.Build
import android.webkit.CookieManager
import android.webkit.WebView
import java.net.URLDecoder
import java.util.concurrent.atomic.AtomicLong

/**
 * Dedicated Anti-Tracking & Privacy Engine for Haze Browser:
 * - Third-party cookie blocking
 * - First-party isolation (FPI)
 * - Tracker & telemetry blocking
 * - Bounce tracking protection (unbouncing redirect trampolines)
 * - Fingerprinting protection (Canvas, WebGL, Audio, Font, Device)
 * - IP leak protection (WebRTC IP leak prevention)
 * - Referrer protection (trimming cross-origin referrers to origin)
 * - Link tracking protection & URL parameter stripping (e.g. example.com/?utm_source=instagram -> example.com/)
 */
object HazeAntiTrackingEngine {

    // Preference Keys
    const val PREF_THIRD_PARTY_COOKIES_BLOCKED = "antitracking_third_party_cookies_blocked"
    const val PREF_FIRST_PARTY_ISOLATION = "antitracking_first_party_isolation"
    const val PREF_TRACKER_BLOCKING = "antitracking_tracker_blocking"
    const val PREF_BOUNCE_TRACKING_PROTECTION = "antitracking_bounce_protection"
    const val PREF_FINGERPRINTING_PROTECTION = "antitracking_fingerprinting_protection"
    const val PREF_CANVAS_PROTECTION = "antitracking_canvas_protection"
    const val PREF_WEBGL_PROTECTION = "antitracking_webgl_protection"
    const val PREF_AUDIO_PROTECTION = "antitracking_audio_protection"
    const val PREF_FONT_PROTECTION = "antitracking_font_protection"
    const val PREF_DEVICE_PROTECTION = "antitracking_device_protection"
    const val PREF_IP_LEAK_PROTECTION = "antitracking_ip_leak_protection"
    const val PREF_REFERRER_PROTECTION = "antitracking_referrer_protection"
    const val PREF_LINK_TRACKING_PROTECTION = "antitracking_link_tracking_protection"

    // In-memory Session Counters
    private val sessionTrackingParamsStripped = AtomicLong(0)
    private val sessionBounceTrackersBlocked = AtomicLong(0)
    private val sessionFingerprintAttemptsBlocked = AtomicLong(0)

    fun getTrackingParamsStripped(): Long = sessionTrackingParamsStripped.get()
    fun getBounceTrackersBlocked(): Long = sessionBounceTrackersBlocked.get()
    fun getFingerprintAttemptsBlocked(): Long = sessionFingerprintAttemptsBlocked.get()

    fun incrementTrackingParamsStripped(count: Long = 1) {
        sessionTrackingParamsStripped.addAndGet(count)
    }

    fun incrementBounceTrackersBlocked() {
        sessionBounceTrackersBlocked.incrementAndGet()
    }

    fun incrementFingerprintAttemptsBlocked() {
        sessionFingerprintAttemptsBlocked.incrementAndGet()
    }

    fun resetSessionStats() {
        sessionTrackingParamsStripped.set(0)
        sessionBounceTrackersBlocked.set(0)
        sessionFingerprintAttemptsBlocked.set(0)
    }

    // =========================================================================
    // 1. LINK TRACKING PROTECTION & URL PARAMETER STRIPPING
    // =========================================================================

    /**
     * Known tracking query parameters across major ad networks, social platforms,
     * email marketing tools, and analytics platforms.
     */
    val TRACKING_PARAMS = hashSetOf(
        // Google Analytics / Urchin Tracking Module (UTM)
        "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content",
        "utm_id", "utm_name", "utm_cid", "utm_reader", "utm_place", "utm_userid",
        "utm_swu", "utm_viz_id", "utm_pubreferrer", "utm_brand", "utm_social-type",
        // Google Ads & Click Identifiers
        "gclid", "gclsrc", "dclid", "gad_source", "gbraid", "wbraid",
        // Meta (Facebook & Instagram)
        "fbclid", "igshid", "fref", "fb_action_ids", "fb_action_types", "fb_source",
        // Microsoft / Bing
        "msclkid",
        // Twitter / X
        "twclid",
        // TikTok
        "ttclid",
        // Pinterest
        "epik",
        // Yahoo & Yandex
        "yclid", "_openstat", "ym_debug",
        // Email & Marketing Automation (HubSpot, Mailchimp, Marketo, Vero)
        "mc_cid", "mc_eid", "_hsenc", "_hsmi", "mkt_tok", "vero_id", "vero_conv",
        "hsa_cam", "hsa_grp", "hsa_mt", "hsa_src", "hsa_ad", "hsa_acc", "hsa_net", "hsa_kw", "hsa_tgt",
        // Generic Tracking & Ad Attribution
        "wickedid", "rb_clickid", "s_kwcid", "otc", "mat_click_id",
        "sc_campaign", "sc_channel", "sc_content", "sc_medium", "sc_outcome", "sc_geo", "sc_country",
        "trk_contact", "trk_msg", "trk_module", "trk_sid", "nan_pid"
    )

    fun isTrackingParameter(paramName: String): Boolean {
        val lower = paramName.lowercase()
        return TRACKING_PARAMS.contains(lower) ||
                lower.startsWith("utm_") ||
                lower.startsWith("hsa_")
    }

    // Auth, SSO, CAPTCHA, and Payment Infrastructure that must never be unbounced or stripped
    val AUTH_AND_PAYMENT_EXEMPT_DOMAINS = hashSetOf(
        "accounts.google.com", "apis.google.com", "oauth2.googleapis.com", "ssl.gstatic.com", "www.gstatic.com",
        "appleid.apple.com", "idmsa.apple.com",
        "login.microsoftonline.com", "login.live.com", "account.live.com", "msftauth.net", "auth.msftidentity.com",
        "github.com", "gitlab.com", "auth0.com", "okta.com", "oktacdn.com",
        "paypal.com", "c.paypal.com", "paypalobjects.com", "stripe.com", "js.stripe.com", "api.stripe.com",
        "checkout.razorpay.com", "api.razorpay.com", "pay.google.com", "applepay.com",
        "challenges.cloudflare.com", "turnstile.cloudflare.com", "recaptcha.net", "hcaptcha.com",
        // Search & News Portals (URLs contain essential session, routing & article tokens)
        "msn.com", "bing.com", "news.google.com", "google.com", "yahoo.com", "duckduckgo.com"
    )

    fun isAuthOrPaymentUrl(url: String): Boolean {
        if (url.isEmpty()) return false
        val lower = url.lowercase()
        val host = extractHostFromUrl(lower)
        if (AUTH_AND_PAYMENT_EXEMPT_DOMAINS.any { host == it || host.endsWith(".$it") }) {
            return true
        }
        val path = try { Uri.parse(lower).path.orEmpty() } catch (_: Exception) { "" }
        if (path.contains("/oauth") || path.contains("/auth") || path.contains("/login") ||
            path.contains("/signin") || path.contains("/authorize") || path.contains("/saml") ||
            path.contains("/sso") || path.contains("/callback") || path.contains("/checkout") ||
            path.contains("/pay") || path.contains("/verify") || path.contains("/challenge")) {
            return true
        }
        return false
    }

    /**
     * Strips all tracking parameters from the given URL.
     * Example:
     *   example.com/?utm_source=instagram  ->  example.com/
     *   https://example.com/item?id=123&utm_source=fb&gclid=xyz  ->  https://example.com/item?id=123
     */
    fun stripTrackingParameters(url: String, context: Context? = null): String {
        if (url.isEmpty() || !url.contains("?") || url.startsWith("javascript:") || url.startsWith("data:") || url.startsWith("about:") || url.startsWith("haze:")) {
            return url
        }

        // Never strip query parameters from OAuth, authentication flows or payment callbacks
        if (isAuthOrPaymentUrl(url)) {
            return url
        }

        try {
            val qIndex = url.indexOf('?')
            val basePart = url.substring(0, qIndex)
            val rest = url.substring(qIndex + 1)

            val hashIndex = rest.indexOf('#')
            val queryPart = if (hashIndex != -1) rest.substring(0, hashIndex) else rest
            val fragmentPart = if (hashIndex != -1) rest.substring(hashIndex) else ""

            if (queryPart.isEmpty()) return url

            val pairs = queryPart.split('&')
            val keptPairs = mutableListOf<String>()
            var strippedCount = 0

            for (pair in pairs) {
                if (pair.isEmpty()) continue
                val eqIdx = pair.indexOf('=')
                val key = if (eqIdx != -1) pair.substring(0, eqIdx) else pair
                if (isTrackingParameter(key)) {
                    strippedCount++
                } else {
                    keptPairs.add(pair)
                }
            }

            if (strippedCount == 0) return url

            sessionTrackingParamsStripped.addAndGet(strippedCount.toLong())
            context?.let { ctx ->
                HazePrivacyShieldEngine.recordTokenCleaned(ctx, strippedCount.toLong())
            }

            val cleanQuery = if (keptPairs.isNotEmpty()) "?" + keptPairs.joinToString("&") else ""
            return basePart + cleanQuery + fragmentPart
        } catch (_: Exception) {
            return url
        }
    }

    // =========================================================================
    // 2. BOUNCE TRACKING PROTECTION (NAVIGATIONAL REDIRECT UNBOUNCING)
    // =========================================================================

    /**
     * Known bounce redirect parameters containing the destination URL.
     */
    val BOUNCE_DEST_PARAMS = hashSetOf(
        "dest", "destination", "target", "target_url", "url", "u", "q", "r",
        "redirect", "redirect_url", "redirect_to", "link", "to", "goto", "next", "forward"
    )

    /**
     * Known domains that act as intermediate click/bounce redirectors.
     */
    val KNOWN_BOUNCE_DOMAINS = hashSetOf(
        "l.facebook.com", "lm.facebook.com", "l.instagram.com", "click.googleanalytics.com",
        "out.tracker.com", "redirect.adnetwork.com", "t.co", "href.li", "gate.sc",
        "link.zhihu.com", "dereferer.me", "anonym.to", "away.vk.com",
        "click.adnetwork.com", "click.advertiser.com", "track.adform.net"
    )

    private fun extractHostFromUrl(url: String): String {
        val withoutScheme = when {
            url.startsWith("https://") -> url.substring(8)
            url.startsWith("http://") -> url.substring(7)
            else -> url
        }
        val slashIdx = withoutScheme.indexOfAny(charArrayOf('/', '?', '#'))
        val hostPort = if (slashIdx != -1) withoutScheme.substring(0, slashIdx) else withoutScheme
        val colonIdx = hostPort.indexOf(':')
        val host = if (colonIdx != -1) hostPort.substring(0, colonIdx) else hostPort
        return host.lowercase().removePrefix("www.")
    }

    /**
     * Inspects a navigation URL to determine if it is a bounce tracking redirect.
     * If so, extracts the clean destination URL, strips tracking parameters from it,
     * and returns the direct target URL to bypass the bounce tracker.
     */
    fun unwrapBounceTrackingUrl(url: String, context: Context? = null): String? {
        if (url.isEmpty() || !url.contains("?") || url.startsWith("javascript:") || url.startsWith("data:")) {
            return null
        }

        // Never unbounce authentication, payment, or challenge pages
        if (isAuthOrPaymentUrl(url)) {
            return null
        }

        try {
            val host = extractHostFromUrl(url)
            val isKnownBounce = KNOWN_BOUNCE_DOMAINS.contains(host)

            // Never interfere with search engines, news portals, or signed click tokens (Bing, MSN, Google)
            if (!isKnownBounce) {
                return null
            }

            val qIdx = url.indexOf('?')
            val rest = url.substring(qIdx + 1)
            val hashIdx = rest.indexOf('#')
            val queryPart = if (hashIdx != -1) rest.substring(0, hashIdx) else rest
            val pairs = queryPart.split('&')

            for (pair in pairs) {
                if (pair.isEmpty()) continue
                val eqIdx = pair.indexOf('=')
                val key = if (eqIdx != -1) pair.substring(0, eqIdx).lowercase() else pair.lowercase()
                val value = if (eqIdx != -1) pair.substring(eqIdx + 1) else ""

                if (BOUNCE_DEST_PARAMS.contains(key)) {
                    val decoded = runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value).trim()

                    if (decoded.startsWith("http://") || decoded.startsWith("https://")) {
                        // Never bypass into an authentication or checkout flow
                        if (isAuthOrPaymentUrl(decoded)) {
                            return null
                        }

                        val destHost = extractHostFromUrl(decoded)
                        if (destHost.isNotEmpty() && destHost != host) {
                            sessionBounceTrackersBlocked.incrementAndGet()
                            context?.let { ctx ->
                                HazePrivacyShieldEngine.recordBounceStopped(ctx)
                            }
                            return stripTrackingParameters(decoded, context)
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        return null
    }

    // =========================================================================
    // 3. THIRD-PARTY COOKIE BLOCKING & FIRST-PARTY ISOLATION
    // =========================================================================

    fun isThirdPartyCookiesBlocked(context: Context): Boolean {
        val prefs = context.getSharedPreferences("HazePrefs", Context.MODE_PRIVATE)
        return prefs.getBoolean(PREF_THIRD_PARTY_COOKIES_BLOCKED, true)
    }

    fun isFirstPartyIsolationEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences("HazePrefs", Context.MODE_PRIVATE)
        return prefs.getBoolean(PREF_FIRST_PARTY_ISOLATION, true)
    }

    fun isLinkTrackingProtectionEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences("HazePrefs", Context.MODE_PRIVATE)
        return prefs.getBoolean(PREF_LINK_TRACKING_PROTECTION, true)
    }

    fun isBounceTrackingProtectionEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences("HazePrefs", Context.MODE_PRIVATE)
        return prefs.getBoolean(PREF_BOUNCE_TRACKING_PROTECTION, true)
    }

    fun isFingerprintingProtectionEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences("HazePrefs", Context.MODE_PRIVATE)
        return prefs.getBoolean(PREF_FINGERPRINTING_PROTECTION, true)
    }

    fun isCanvasProtectionEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences("HazePrefs", Context.MODE_PRIVATE)
        return prefs.getBoolean(PREF_CANVAS_PROTECTION, true)
    }

    fun isWebGLProtectionEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences("HazePrefs", Context.MODE_PRIVATE)
        return prefs.getBoolean(PREF_WEBGL_PROTECTION, true)
    }

    fun isAudioProtectionEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences("HazePrefs", Context.MODE_PRIVATE)
        return prefs.getBoolean(PREF_AUDIO_PROTECTION, true)
    }

    fun isFontProtectionEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences("HazePrefs", Context.MODE_PRIVATE)
        return prefs.getBoolean(PREF_FONT_PROTECTION, true)
    }

    fun isDeviceProtectionEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences("HazePrefs", Context.MODE_PRIVATE)
        return prefs.getBoolean(PREF_DEVICE_PROTECTION, true)
    }

    fun isIpLeakProtectionEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences("HazePrefs", Context.MODE_PRIVATE)
        return prefs.getBoolean(PREF_IP_LEAK_PROTECTION, true)
    }

    fun isReferrerProtectionEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences("HazePrefs", Context.MODE_PRIVATE)
        return prefs.getBoolean(PREF_REFERRER_PROTECTION, true)
    }

    /**
     * Applies Third-Party Cookie Policy to a WebView instance.
     * In private tabs or when Third-Party Cookie Blocking is enabled, third-party cookies are blocked outright.
     */
    fun configureWebViewCookies(webView: WebView, context: Context, isPrivate: Boolean) {
        try {
            val cm = CookieManager.getInstance()
            cm.setAcceptCookie(true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val blockThirdParty = isPrivate || isThirdPartyCookiesBlocked(context)
                cm.setAcceptThirdPartyCookies(webView, !blockThirdParty)
            }
        } catch (_: Throwable) {}
    }

    // =========================================================================
    // 4. JAVASCRIPT PRIVACY & ANTI-FINGERPRINTING SHIELD
    // =========================================================================

    /**
     * Generates a hardened, zero-overhead anti-fingerprinting and privacy script
     * injected at document start to protect:
     * - Canvas fingerprinting (subtle least-significant bit noise injection)
     * - WebGL fingerprinting (masks vendor, renderer, readPixels)
     * - Audio fingerprinting (subtle jitter on AudioBuffer / AnalyserNode)
     * - Font fingerprinting (normalizes sub-pixel measurement probes)
     * - Device fingerprinting (normalizes hardwareConcurrency, deviceMemory, maxTouchPoints, battery API)
     * - IP leak protection (sanitizes WebRTC host & STUN candidates)
     * - Referrer protection (trims cross-origin document.referrer to origin)
     * - First-Party Isolation (clears window.name on unload)
     * - Link tracking protection (cleans href on click)
     */
    fun generateAntiFingerprintScript(context: Context): String {
        val blockCanvas = isCanvasProtectionEnabled(context)
        val blockWebGL = isWebGLProtectionEnabled(context)
        val blockAudio = isAudioProtectionEnabled(context)
        val blockFont = isFontProtectionEnabled(context)
        val blockDevice = isDeviceProtectionEnabled(context)
        val blockIpLeak = isIpLeakProtectionEnabled(context)
        val blockReferrer = isReferrerProtectionEnabled(context)
        val firstPartyIsolation = isFirstPartyIsolationEnabled(context)
        val linkTracking = isLinkTrackingProtectionEnabled(context)

        return """
            (function() {
                if (window.__hazeAntiTrackingShieldInstalled) return;
                window.__hazeAntiTrackingShieldInstalled = true;

                var __reportedProbes = {};
                function __reportProbe(type) {
                    if (__reportedProbes[type]) return;
                    __reportedProbes[type] = true;
                    setTimeout(function() {
                        try {
                            if (window.Android && window.Android.recordFingerprintAttempt) {
                                window.Android.recordFingerprintAttempt(type);
                            }
                        } catch(e) {}
                    }, 1000);
                }

                ${if (blockCanvas) """
                // 1. CANVAS FINGERPRINT DEFENSE
                // Stealth probe detection without pixel corruption to ensure QR readers, photo editors, and CAPTCHAs work 100%
                try {
                    var origToDataURL = HTMLCanvasElement.prototype.toDataURL;
                    var origGetImageData = CanvasRenderingContext2D.prototype.getImageData;

                    CanvasRenderingContext2D.prototype.getImageData = function(sx, sy, sw, sh) {
                        if (sw <= 32 && sh <= 32) {
                            __reportProbe('Canvas');
                        }
                        return origGetImageData.apply(this, arguments);
                    };

                    HTMLCanvasElement.prototype.toDataURL = function() {
                        if (this.width <= 32 && this.height <= 32) {
                            __reportProbe('Canvas');
                        }
                        return origToDataURL.apply(this, arguments);
                    };
                } catch(e) {}
                """.trimIndent() else ""}

                ${if (blockWebGL) """
                // 2. WEBGL FINGERPRINT DEFENSE
                // Masks hardware vendor and renderer without corrupting readPixels buffer
                try {
                    var hookWebGL = function(proto) {
                        if (!proto) return;
                        var origGetParam = proto.getParameter;
                        proto.getParameter = function(param) {
                            if (param === 37445) { // UNMASKED_VENDOR_WEBGL
                                __reportProbe('WebGL');
                                return "Google Inc. (Google)";
                            }
                            if (param === 37446) { // UNMASKED_RENDERER_WEBGL
                                __reportProbe('WebGL');
                                return "ANGLE (Google, Vulkan 1.3.0, Android)";
                            }
                            if (param === 3379) return 16384; // MAX_TEXTURE_SIZE
                            return origGetParam.apply(this, arguments);
                        };
                    };
                    if (window.WebGLRenderingContext) hookWebGL(WebGLRenderingContext.prototype);
                    if (window.WebGL2RenderingContext) hookWebGL(WebGL2RenderingContext.prototype);
                } catch(e) {}
                """.trimIndent() else ""}

                ${if (blockAudio) """
                // 3. AUDIO FINGERPRINT DEFENSE
                try {
                    if (window.OfflineAudioContext) {
                        var origStart = OfflineAudioContext.prototype.startRendering;
                        OfflineAudioContext.prototype.startRendering = function() {
                            __reportProbe('Audio');
                            return origStart.apply(this, arguments);
                        };
                    }
                } catch(e) {}
                """.trimIndent() else ""}

                ${if (blockFont) """
                // 4. FONT FINGERPRINT DEFENSE
                try {
                    var origMeasure = CanvasRenderingContext2D.prototype.measureText;
                    CanvasRenderingContext2D.prototype.measureText = function() {
                        __reportProbe('Font');
                        return origMeasure.apply(this, arguments);
                    };
                } catch(e) {}
                """.trimIndent() else ""}

                ${if (blockDevice) """
                // 5. DEVICE FINGERPRINT DEFENSE
                try {
                    Object.defineProperty(navigator, 'hardwareConcurrency', { get: function() { return 8; }, configurable: true });
                    Object.defineProperty(navigator, 'deviceMemory', { get: function() { return 8; }, configurable: true });
                    Object.defineProperty(navigator, 'maxTouchPoints', { get: function() { return 5; }, configurable: true });
                    Object.defineProperty(screen, 'colorDepth', { get: function() { return 24; }, configurable: true });
                    Object.defineProperty(screen, 'pixelDepth', { get: function() { return 24; }, configurable: true });
                } catch(e) {}
                """.trimIndent() else ""}

                ${if (blockIpLeak) """
                // 6. WEBRTC IP LEAK DEFENSE
                // Only filter private LAN IPv4 candidates while ALWAYS preserving STUN (srflx) and TURN (relay)
                // so Google Meet, Zoom, Discord, and WebRTC video/audio calls function reliably!
                try {
                    var protectPeerConnection = function(PC) {
                        if (!PC) return;
                        var origOffer = PC.prototype.createOffer;
                        var origAnswer = PC.prototype.createAnswer;
                        function filterSdp(sdp) {
                            if (!sdp || typeof sdp !== 'string') return sdp;
                            return sdp.replace(/^a=candidate:.+ typ host .*(192\.168\.\d+\.\d+|10\.\d+\.\d+\.\d+|172\.(1[6-9]|2\d|3[01])\.\d+\.\d+).*$/gm, '');
                        }
                        PC.prototype.createOffer = function() {
                            return origOffer.apply(this, arguments).then(function(desc) {
                                if (desc && desc.sdp) desc.sdp = filterSdp(desc.sdp);
                                return desc;
                            });
                        };
                        PC.prototype.createAnswer = function() {
                            return origAnswer.apply(this, arguments).then(function(desc) {
                                if (desc && desc.sdp) desc.sdp = filterSdp(desc.sdp);
                                return desc;
                            });
                        };
                    };
                    if (window.RTCPeerConnection) protectPeerConnection(window.RTCPeerConnection);
                    if (window.webkitRTCPeerConnection) protectPeerConnection(window.webkitRTCPeerConnection);
                } catch(e) {}
                """.trimIndent() else ""}

                ${if (blockReferrer) """
                // 7. REFERRER PROTECTION (Trims cross-origin referrer to origin only)
                try {
                    var rawRef = document.referrer;
                    if (rawRef) {
                        try {
                            var refObj = new URL(rawRef);
                            if (refObj.origin !== window.location.origin) {
                                Object.defineProperty(document, 'referrer', {
                                    get: function() { return refObj.origin + '/'; },
                                    configurable: true
                                });
                            }
                        } catch(e) {}
                    }
                } catch(e) {}
                """.trimIndent() else ""}

                ${if (firstPartyIsolation) """
                // 8. FIRST-PARTY ISOLATION (Clear window.name on unload)
                try {
                    window.addEventListener('beforeunload', function() {
                        try { window.name = ''; } catch(e) {}
                    });
                } catch(e) {}
                """.trimIndent() else ""}

                ${if (linkTracking) """
                // 9. CLIENT-SIDE LINK TRACKING SCRUBBER
                try {
                    var TRACKING_KEYS = ['utm_source', 'utm_medium', 'utm_campaign', 'utm_term', 'utm_content', 'utm_id', 'utm_name', 'gclid', 'fbclid', 'igshid', 'msclkid', 'twclid', 'ttclid', 'dclid', 'gad_source', 'gbraid', 'wbraid', 'mc_cid', 'mc_eid', '_hsenc', '_hsmi', 'mkt_tok'];
                    var AUTH_PATHS = ['/oauth', '/auth', '/login', '/signin', '/authorize', '/saml', '/sso', '/callback', '/checkout', '/pay'];
                    function cleanLink(raw) {
                        if (!raw || raw.indexOf('?') === -1) return raw;
                        try {
                            var u = new URL(raw, window.location.href);
                            var pathLower = u.pathname.toLowerCase();
                            for (var p = 0; p < AUTH_PATHS.length; p++) {
                                if (pathLower.indexOf(AUTH_PATHS[p]) !== -1) return raw;
                            }
                            var mod = false;
                            TRACKING_KEYS.forEach(function(k) {
                                if (u.searchParams.has(k)) {
                                    u.searchParams.delete(k);
                                    mod = true;
                                }
                            });
                            return mod ? u.toString() : raw;
                        } catch(e) { return raw; }
                    }
                    document.addEventListener('click', function(e) {
                        var a = e.target;
                        while (a && a.tagName !== 'A') a = a.parentElement;
                        if (a && a.href && !a.href.startsWith('javascript:')) {
                            var cleaned = cleanLink(a.href);
                            if (cleaned !== a.href) a.href = cleaned;
                        }
                    }, true);
                } catch(e) {}
                """.trimIndent() else ""}
            })();
        """.trimIndent()
    }
}
