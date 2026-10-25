package com.example.haze.engine.chromium

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Message
import android.view.View
import android.webkit.*
import com.example.haze.MainActivity
import com.example.haze.HazeSecurityManager
import com.example.haze.HazePrivacyShieldEngine
import com.example.haze.HazeAntiTrackingEngine
import com.example.haze.HazeFilterListManager
import com.example.haze.HazeSecurityWarningManager
import com.example.haze.HazeDevToolsManager
import com.example.haze.HazeIntelligentCacheManager
import com.example.haze.engine.*
import java.io.ByteArrayInputStream

/**
 * Chromium-backed browser tab session.
 */
@SuppressLint("SetJavaScriptEnabled")
class ChromiumBrowserTab(
    context: Context,
    override val id: Long = System.currentTimeMillis(),
    override val isPrivate: Boolean = false,
    config: EngineConfig = EngineConfig()
) : IBrowserTab {

    val webView: WebView = object : WebView(context) {
        override fun onWindowVisibilityChanged(visibility: Int) {
            super.onWindowVisibilityChanged(visibility)
            if (visibility == View.VISIBLE) {
                onResume()
            } else {
                val isMediaActive = (context as? MainActivity)?.isMediaPlaying == true
                if (!isMediaActive) {
                    onPause()
                }
            }
        }

        override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) {
            super.onScrollChanged(l, t, oldl, oldt)
            observer?.onScrollChanged(this@ChromiumBrowserTab, l, t, oldl, oldt)
        }
    }

    override val renderView: View get() = webView

    override val navigationController: INavigationController = ChromiumNavigationController(webView)

    override var title: String = "New Tab"
    override var url: String = "haze://home"
    override var favicon: Bitmap? = null
    override var isDesktop: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                applyDesktopMode(value)
            }
        }

    override var progress: Int = 0
        private set

    private var observer: IEngineObserver? = null

    init {
        if (isPrivate) {
            try {
                if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.MULTI_PROFILE)) {
                    val store = androidx.webkit.ProfileStore.getInstance()
                    store.getOrCreateProfile("incognito")
                    androidx.webkit.WebViewCompat.setProfile(webView, "incognito")
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        try {
            if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT)) {
                androidx.webkit.WebViewCompat.addDocumentStartJavaScript(
                    webView,
                    YOUTUBE_ENGINE_SCRIPT,
                    setOf("https://*.youtube.com", "https://youtube.com", "https://*.youtube-nocookie.com")
                )
                androidx.webkit.WebViewCompat.addDocumentStartJavaScript(
                    webView,
                    ADGUARD_UNIVERSAL_SHIELD_SCRIPT,
                    setOf("*")
                )
                androidx.webkit.WebViewCompat.addDocumentStartJavaScript(
                    webView,
                    SOCIAL_SHORT_VIDEO_PROFILE_SCRIPT,
                    setOf(
                        "https://*.youtube.com", "https://youtube.com",
                        "https://*.instagram.com", "https://instagram.com",
                        "https://*.facebook.com", "https://facebook.com",
                        "https://*.tiktok.com", "https://tiktok.com"
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        configureWebSettings(config)
        setupClientHandlers()
    }

    private fun configureWebSettings(config: EngineConfig) {
        HazeSecurityManager.configureSessionSecurity(webView)
        HazeAntiTrackingEngine.configureWebViewCookies(webView, webView.context, isPrivate)
        HazeSecurityManager.applyBrowserIsolation(webView)

        webView.settings.apply {
            javaScriptEnabled = config.enableJavaScript
            domStorageEnabled = config.enableDomStorage
            databaseEnabled = config.enableDatabase
            useWideViewPort = isDesktop
            loadWithOverviewMode = isDesktop
            // Start with a clean Android Chrome UA; avoids sites detecting raw WebView UA
            userAgentString = if (isDesktop) DESKTOP_USER_AGENT else MOBILE_USER_AGENT
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            allowFileAccess = false
            allowContentAccess = false
            mediaPlaybackRequiresUserGesture = false
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(true)
            defaultTextEncodingName = config.defaultEncoding

            // Chromium & Google Password Manager integration
            saveFormData = !isPrivate
            savePassword = !isPrivate

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                safeBrowsingEnabled = true
                webView.importantForAutofill = if (isPrivate) View.IMPORTANT_FOR_AUTOFILL_NO else View.IMPORTANT_FOR_AUTOFILL_YES
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            }
            if (isPrivate) {
                cacheMode = WebSettings.LOAD_NO_CACHE
            } else {
                cacheMode = WebSettings.LOAD_DEFAULT
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                // Fluid rasterization for buttery smooth 90Hz/120Hz scrolling
                offscreenPreRaster = true
            }
            @Suppress("DEPRECATION")
            setRenderPriority(WebSettings.RenderPriority.HIGH)
        }

        // Native Direct RenderThread Acceleration (Hardware-accelerated scrolling)
        webView.setLayerType(View.LAYER_TYPE_NONE, null)
        webView.overScrollMode = View.OVER_SCROLL_ALWAYS
        webView.isNestedScrollingEnabled = true
        webView.isVerticalScrollBarEnabled = false
        webView.isHorizontalScrollBarEnabled = false

        val isDark = isPrivate || webView.context.getSharedPreferences("HazePrefs", Context.MODE_PRIVATE).getBoolean("is_dark", true)
        webView.setBackgroundColor(if (isDark) android.graphics.Color.parseColor("#121212") else android.graphics.Color.WHITE)
        try {
            if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.ALGORITHMIC_DARKENING)) {
                androidx.webkit.WebSettingsCompat.setAlgorithmicDarkeningAllowed(webView.settings, isDark)
            }
            if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.FORCE_DARK)) {
                @Suppress("DEPRECATION")
                androidx.webkit.WebSettingsCompat.setForceDark(
                    webView.settings,
                    if (isDark) androidx.webkit.WebSettingsCompat.FORCE_DARK_ON else androidx.webkit.WebSettingsCompat.FORCE_DARK_OFF
                )
            }
            if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.FORCE_DARK_STRATEGY)) {
                @Suppress("DEPRECATION")
                androidx.webkit.WebSettingsCompat.setForceDarkStrategy(
                    webView.settings,
                    androidx.webkit.WebSettingsCompat.DARK_STRATEGY_PREFER_WEB_THEME_OVER_USER_AGENT_DARKENING
                )
            }
        } catch (_: Exception) {}
    }

    private val defaultMobileUserAgent: String
        get() = MOBILE_USER_AGENT

    private fun applyDesktopMode(desktop: Boolean) {
        val currentUrl = webView.url ?: url
        val isSpotify = currentUrl.contains("spotify.com", ignoreCase = true)

        if (desktop) {
            webView.settings.userAgentString = if (isSpotify) SPOTIFY_DESKTOP_UA else DESKTOP_USER_AGENT
            webView.settings.useWideViewPort = true
            webView.settings.loadWithOverviewMode = true
        } else {
            webView.settings.userAgentString = MOBILE_USER_AGENT
            webView.settings.useWideViewPort = false
            webView.settings.loadWithOverviewMode = false
        }

        // When toggling, reload the page — but first correct the URL for YouTube desktop
        var reloadUrl = currentUrl
        if (desktop && (currentUrl.contains("m.youtube.com", ignoreCase = true))) {
            reloadUrl = currentUrl.replace("m.youtube.com", "www.youtube.com", ignoreCase = true)
        } else if (!desktop && currentUrl.contains("www.youtube.com", ignoreCase = true) && !currentUrl.contains("youtube.com/tv", ignoreCase = true)) {
            // Keep www.youtube.com even in mobile mode — the mobile UA causes YouTube to serve responsive layout
            reloadUrl = currentUrl
        }

        if (reloadUrl != currentUrl) {
            webView.loadUrl(reloadUrl)
        } else {
            navigationController.reload()
        }
    }

    private fun setupClientHandlers() {
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, pageUrl: String?, faviconBitmap: Bitmap?) {
                val nonNullUrl = pageUrl ?: ""
                url = nonNullUrl
                if (!nonNullUrl.startsWith("haze://") && !nonNullUrl.startsWith("about:")) {
                    view?.evaluateJavascript(com.example.haze.HazeFluidCacheEngine.SAFARI_FLUID_ENGINE_SCRIPT, null)
                    HazeIntelligentCacheManager.configureCacheMode(webView, nonNullUrl)
                    val isSpotify = nonNullUrl.contains("spotify.com", ignoreCase = true)
                    val isYouTube = nonNullUrl.contains("youtube.com", ignoreCase = true)

                    // Enforce correct UA only when changed (prevents reload loop and aborted requests)
                    val targetUa = if (isSpotify) {
                        try {
                            android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
                        } catch (_: Throwable) {}
                        if (isDesktop) SPOTIFY_DESKTOP_UA else MOBILE_USER_AGENT
                    } else {
                        if (isDesktop) DESKTOP_USER_AGENT else MOBILE_USER_AGENT
                    }
                    view?.settings?.let { s ->
                        if (s.userAgentString != targetUa) s.userAgentString = targetUa
                        if (s.useWideViewPort != isDesktop) s.useWideViewPort = isDesktop
                        if (s.loadWithOverviewMode != isDesktop) s.loadWithOverviewMode = isDesktop
                    }

                    // Inject scripts after UA is applied
                    if (isYouTube) {
                        view?.evaluateJavascript(YOUTUBE_ENGINE_SCRIPT, null)
                    }
                    if (isSpotify) {
                        view?.evaluateJavascript(SPOTIFY_ENGINE_SCRIPT, null)
                    }

                    // Inject viewport + client-hints override scripts
                    if (isDesktop) {
                        view?.evaluateJavascript(DESKTOP_VIEWPORT_SCRIPT, null)
                        view?.evaluateJavascript(DESKTOP_CLIENT_HINTS_SCRIPT, null)
                    } else {
                        view?.evaluateJavascript(MOBILE_VIEWPORT_SCRIPT, null)
                    }

                    val script = com.example.haze.HazeAntiTrackingEngine.generateAntiFingerprintScript(webView.context)
                    view?.evaluateJavascript(script, null)

                    if (HazePrivacyShieldEngine.isShieldEnabled(webView.context)) {
                        val domain = runCatching { Uri.parse(nonNullUrl).host }.getOrNull()
                        val compiledCosmetic = HazeFilterListManager.getCosmeticCss(webView.context, domain)
                        if (compiledCosmetic.isNotEmpty()) {
                            val escapedCss = compiledCosmetic.replace("\\", "\\\\").replace("'", "\\'").replace("\n", " ")
                            val earlyCss = """
                                (function() {
                                    var s = document.getElementById('__haze_cosmetic_style');
                                    if (!s) {
                                        s = document.createElement('style');
                                        s.id = '__haze_cosmetic_style';
                                        s.type = 'text/css';
                                        (document.head || document.documentElement).appendChild(s);
                                    }
                                    s.textContent = '$escapedCss';
                                })();
                            """.trimIndent()
                            view?.evaluateJavascript(earlyCss, null)
                        }
                        view?.evaluateJavascript(DYNAMIC_BANNER_CLEANER_SCRIPT, null)
                    }
                }
                observer?.onPageStarted(this@ChromiumBrowserTab, nonNullUrl, faviconBitmap)
            }

            override fun onPageFinished(view: WebView?, pageUrl: String?) {
                val nonNullUrl = pageUrl ?: ""
                url = nonNullUrl
                title = view?.title ?: title
                val isSpotify = nonNullUrl.contains("spotify.com", ignoreCase = true)
                val isYouTube = nonNullUrl.contains("youtube.com", ignoreCase = true)

                // Re-inject site-specific engine scripts after page load completes
                if (isYouTube) {
                    view?.evaluateJavascript(YOUTUBE_ENGINE_SCRIPT, null)
                }
                if (isSpotify) {
                    view?.evaluateJavascript(SPOTIFY_ENGINE_SCRIPT, null)
                }

                // Re-apply viewport and client-hints after full load (SPAs may reset viewport meta)
                if (isDesktop) {
                    view?.evaluateJavascript(DESKTOP_VIEWPORT_SCRIPT, null)
                    view?.evaluateJavascript(DESKTOP_CLIENT_HINTS_SCRIPT, null)
                } else {
                    view?.evaluateJavascript(MOBILE_VIEWPORT_SCRIPT, null)
                }

                if (!nonNullUrl.startsWith("haze://") && !nonNullUrl.startsWith("about:")) {
                    view?.evaluateJavascript(com.example.haze.HazeFluidCacheEngine.SAFARI_FLUID_ENGINE_SCRIPT, null)
                    val domain = runCatching { Uri.parse(nonNullUrl).host }.getOrNull()
                    val isYouTube = domain?.contains("youtube.com", ignoreCase = true) == true
                    if (!isYouTube && HazePrivacyShieldEngine.isShieldEnabled(webView.context)) {
                        val compiledCosmetic = HazeFilterListManager.getCosmeticCss(webView.context, domain)
                        if (compiledCosmetic.isNotEmpty()) {
                            val escapedCss = compiledCosmetic.replace("\\", "\\\\").replace("'", "\\'").replace("\n", " ")
                            val cosmeticCss = """
                                (function() {
                                    var s = document.getElementById('__haze_cosmetic_style');
                                    if (!s) {
                                        s = document.createElement('style');
                                        s.id = '__haze_cosmetic_style';
                                        s.type = 'text/css';
                                        (document.head || document.documentElement).appendChild(s);
                                    }
                                    s.textContent = '$escapedCss';
                                })();
                            """.trimIndent()
                            view?.evaluateJavascript(cosmeticCss, null)
                        }
                        view?.evaluateJavascript(DYNAMIC_BANNER_CLEANER_SCRIPT, null)
                        view?.evaluateJavascript("try { if (window.__hazeCleanAdSections) window.__hazeCleanAdSections(); } catch(_) {}", null)
                    }
                }
                if (nonNullUrl.contains("myaccount.google.com") || 
                    nonNullUrl.contains("accounts.google.com/CheckCookie") ||
                    nonNullUrl.contains("accounts.google.com/ManageAccount")) {
                    com.example.haze.HazeAccountManager.setCompletedGoogleWebLogin(webView.context, true)
                }
                if (nonNullUrl.contains("google.com", ignoreCase = true) || nonNullUrl.contains("youtube.com", ignoreCase = true)) {
                    val extractGoogleAvatarScript = """
                        (function() {
                            function extractGoogleProfile() {
                                try {
                                    var img = document.querySelector('img[src*="googleusercontent.com/a/"], img[src*="googleusercontent.com/ogw/"], img[src*="googleusercontent.com/"], img[src*="yt3.ggpht.com/"], a[aria-label*="Google Account" i] img, button[aria-label*="Google Account" i] img, a[aria-label*="Compte Google" i] img, a[aria-label*="Cuenta de Google" i] img, img[alt*="Google Account" i], img[alt*="Profile photo" i], img[alt*="Account" i], img.gb_m, img.gb_A, .gb_d img, ytm-profile-icon img, button.yt-spec-avatar img');
                                    var avatarUrl = img ? (img.src || '') : '';
                                    
                                    // Also check aria-label or title for display name and email
                                    var accountBtn = document.querySelector('a[aria-label*="Google Account" i], button[aria-label*="Google Account" i], a[aria-label*="@" i]');
                                    var label = accountBtn ? (accountBtn.getAttribute('aria-label') || accountBtn.getAttribute('title') || '') : '';
                                    var email = '';
                                    var name = '';
                                    var emailMatch = label.match(/([a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,})/);
                                    if (emailMatch) {
                                        email = emailMatch[1];
                                    }
                                    if (label && email) {
                                        var parts = label.split('\n');
                                        if (parts.length > 1 && parts[0].trim()) {
                                            name = parts[0].replace(/Google Account:\s*/i, '').trim();
                                        }
                                    }
                                    
                                    if (avatarUrl && window.Android && window.Android.syncGoogleAccountDetails) {
                                        // Request higher resolution (e.g., s192-c instead of s32-c)
                                        var highRes = avatarUrl.replace(/=s\d+(-c)?/i, '=s192-c').replace(/=s\d+/i, '=s192');
                                        window.Android.syncGoogleAccountDetails(email, name, highRes);
                                    }
                                } catch(_) {}
                            }
                            setTimeout(extractGoogleProfile, 600);
                            setTimeout(extractGoogleProfile, 1500);
                            setTimeout(extractGoogleProfile, 3000);
                        })();
                    """.trimIndent()
                    view?.evaluateJavascript(extractGoogleAvatarScript, null)
                }
                HazeSecurityManager.persistCookieStore()
                observer?.onPageFinished(this@ChromiumBrowserTab, nonNullUrl)
            }

            override fun onSafeBrowsingHit(
                view: WebView?,
                request: WebResourceRequest?,
                threatType: Int,
                callback: SafeBrowsingResponse?
            ) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    callback?.showInterstitial(true)
                } else {
                    super.onSafeBrowsingHit(view, request, threatType, callback)
                }
            }

            override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                val desc = description ?: "Unknown error"
                if (errorCode == -3 || desc.contains("ERR_ABORTED", ignoreCase = true)) {
                    return
                }
                observer?.onReceivedError(
                    this@ChromiumBrowserTab,
                    errorCode,
                    desc,
                    failingUrl ?: url
                )
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                if (request?.isForMainFrame == true) {
                    val errorCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) error?.errorCode ?: -1 else -1
                    val desc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) error?.description?.toString() ?: "" else ""
                    if (errorCode == -3 || desc.contains("ERR_ABORTED", ignoreCase = true)) {
                        return
                    }
                    val failingUrl = request.url?.toString() ?: url
                    observer?.onReceivedError(this@ChromiumBrowserTab, errorCode, desc, failingUrl)
                }
            }

            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                val failingUrl = error?.url ?: ""
                val currentUrl = view?.url ?: url
                val failingHost = try { Uri.parse(failingUrl).host?.lowercase() } catch (_: Exception) { null }
                val currentHost = try { Uri.parse(currentUrl).host?.lowercase() } catch (_: Exception) { null }

                if (failingHost != null && currentHost != null && failingHost != currentHost) {
                    handler?.cancel()
                    return
                }

                val cert = error?.certificate
                val sslInfo = SslErrorInfo(
                    primaryError = error?.primaryError ?: -1,
                    url = if (failingUrl.isNotEmpty()) failingUrl else currentUrl,
                    certificateSummary = cert?.toString() ?: "Unknown Certificate",
                    issuedTo = cert?.issuedTo?.dName ?: "Unknown",
                    issuedBy = cert?.issuedBy?.dName ?: "Unknown",
                    validNotBefore = cert?.validNotBeforeDate?.toString() ?: "",
                    validNotAfter = cert?.validNotAfterDate?.toString() ?: ""
                )

                observer?.onSslError(
                    this@ChromiumBrowserTab,
                    sslInfo,
                    proceed = { handler?.proceed() },
                    cancel = { handler?.cancel() }
                ) ?: handler?.cancel()
            }

            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                val didCrash = detail?.didCrash() ?: true
                val handled = observer?.onRenderProcessGone(this@ChromiumBrowserTab, didCrash) ?: false
                return handled
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val reqUrl = request?.url?.toString() ?: return null
                val isMain = request.isForMainFrame
                val obsResult = observer?.shouldInterceptRequest(reqUrl, isMain)
                if (obsResult != null) {
                    return WebResourceResponse(
                        obsResult.mimeType,
                        obsResult.encoding,
                        obsResult.statusCode,
                        obsResult.reasonPhrase,
                        obsResult.responseHeaders,
                        obsResult.data
                    )
                }

                val ctx = webView.context
                val pageUrl = if (isMain) reqUrl else (request.requestHeaders?.get("Referer") ?: request.requestHeaders?.get("referer") ?: url)
                val decision = HazePrivacyShieldEngine.evaluateRequest(
                    context = ctx,
                    requestUrl = reqUrl,
                    pageUrl = pageUrl,
                    isMainFrame = isMain,
                    isPrivate = isPrivate
                )
                if (HazeDevToolsManager.isDevToolsActive()) {
                    HazeDevToolsManager.recordNetworkRequest(
                        tabId = id,
                        url = reqUrl,
                        method = request.method ?: "GET",
                        headers = request.requestHeaders ?: emptyMap(),
                        isMainFrame = isMain,
                        isBlocked = decision.shouldBlock
                    )
                }
                if (decision.shouldBlock) {
                    if (isMain && decision.isHarmfulPageNavigation) {
                        val isDark = (ctx.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES || isPrivate
                        val html = HazeSecurityWarningManager.getSecurityWarningHtml(reqUrl, decision.category, isDark)
                        val bytes = html.toByteArray(Charsets.UTF_8)
                        val headers = mapOf("Content-Type" to "text/html; charset=utf-8")
                        return WebResourceResponse("text/html", "UTF-8", 200, "OK", headers, ByteArrayInputStream(bytes))
                    } else {
                        return HazePrivacyShieldEngine.createBlockedResponse(reqUrl)
                    }
                }

                // Check 1.5 - 2.0 GB Ultra-High-Speed Fluid Cache Engine (0-1ms cache serving for scripts, stylesheets, fonts, icons)
                val cachedResponse = com.example.haze.HazeFluidCacheEngine.interceptRequest(
                    context = ctx,
                    request = request,
                    isPrivate = isPrivate
                )
                if (cachedResponse != null) {
                    return cachedResponse
                }

                // Native Chromium C++ Cache & Network Stack handles all other assets with HTTP/2 & HTTP/3 QUIC
                return null
            }

            private fun handleExternalScheme(view: WebView?, targetUrl: String): Boolean {
                if (targetUrl.startsWith("http://") || targetUrl.startsWith("https://") ||
                    targetUrl.startsWith("about:") || targetUrl.startsWith("data:") ||
                    targetUrl.startsWith("javascript:") || targetUrl.startsWith("haze://")) {
                    return false
                }
                val ctx = view?.context ?: webView.context
                return try {
                    val intent = Intent.parseUri(targetUrl, Intent.URI_INTENT_SCHEME)
                    if (intent != null) {
                        val pm = ctx.packageManager
                        val info = pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
                        if (info != null) {
                            ctx.startActivity(intent)
                            return true
                        }
                        val fallbackUrl = intent.getStringExtra("browser_fallback_url")
                        if (!fallbackUrl.isNullOrEmpty() && (fallbackUrl.startsWith("http://") || fallbackUrl.startsWith("https://"))) {
                            view?.loadUrl(fallbackUrl)
                            return true
                        }
                    }
                    false
                } catch (e: Exception) {
                    true
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                var targetUrl = request?.url?.toString() ?: return false
                if (handleExternalScheme(view, targetUrl)) return true

                val ctx = webView.context
                val isGet = request.method == null || request.method.equals("GET", ignoreCase = true)
                if (isGet && com.example.haze.HazeAntiTrackingEngine.isBounceTrackingProtectionEnabled(ctx)) {
                    val unwrapped = com.example.haze.HazeAntiTrackingEngine.unwrapBounceTrackingUrl(targetUrl, ctx)
                    if (unwrapped != null) {
                        targetUrl = unwrapped
                        view?.loadUrl(targetUrl)
                        return true
                    }
                }
                if (isGet && com.example.haze.HazeAntiTrackingEngine.isLinkTrackingProtectionEnabled(ctx)) {
                    val cleaned = com.example.haze.HazeAntiTrackingEngine.stripTrackingParameters(targetUrl, ctx)
                    if (cleaned != targetUrl) {
                        targetUrl = cleaned
                        view?.loadUrl(targetUrl)
                        return true
                    }
                }
                return observer?.shouldOverrideUrlLoading(targetUrl) ?: false
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                if (consoleMessage != null) {
                    val level = when (consoleMessage.messageLevel()) {
                        ConsoleMessage.MessageLevel.ERROR -> HazeDevToolsManager.ConsoleLogEntry.Level.ERROR
                        ConsoleMessage.MessageLevel.WARNING -> HazeDevToolsManager.ConsoleLogEntry.Level.WARNING
                        ConsoleMessage.MessageLevel.LOG -> HazeDevToolsManager.ConsoleLogEntry.Level.LOG
                        ConsoleMessage.MessageLevel.TIP, ConsoleMessage.MessageLevel.DEBUG -> HazeDevToolsManager.ConsoleLogEntry.Level.INFO
                        else -> HazeDevToolsManager.ConsoleLogEntry.Level.LOG
                    }
                    if (HazeDevToolsManager.isDevToolsActive()) {
                        HazeDevToolsManager.recordConsoleMessage(
                            tabId = id,
                            message = consoleMessage.message(),
                            sourceId = consoleMessage.sourceId(),
                            lineNumber = consoleMessage.lineNumber(),
                            level = level
                        )
                    }
                }
                return super.onConsoleMessage(consoleMessage)
            }

            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progress = newProgress
                observer?.onPageProgress(this@ChromiumBrowserTab, newProgress)
            }

            override fun onReceivedTitle(view: WebView?, pageTitle: String?) {
                if (!pageTitle.isNullOrEmpty()) {
                    title = pageTitle
                    observer?.onTitleReceived(this@ChromiumBrowserTab, pageTitle)
                }
            }

            override fun onReceivedIcon(view: WebView?, icon: Bitmap?) {
                if (icon != null) {
                    favicon = icon
                    observer?.onFaviconReceived(this@ChromiumBrowserTab, icon)
                }
            }

            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (view != null) {
                    observer?.onShowCustomView(view) { callback?.onCustomViewHidden() }
                }
            }

            override fun onHideCustomView() {
                observer?.onHideCustomView()
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                val params = EngineFileChooserParams(
                    mode = fileChooserParams?.mode ?: EngineFileChooserParams.MODE_OPEN,
                    acceptTypes = fileChooserParams?.acceptTypes ?: arrayOf("*/*"),
                    isCaptureEnabled = fileChooserParams?.isCaptureEnabled ?: false,
                    title = fileChooserParams?.title
                )
                return observer?.onShowFileChooser(params) { uris ->
                    filePathCallback?.onReceiveValue(uris)
                } ?: false
            }

            override fun onPermissionRequest(request: PermissionRequest?) {
                if (request == null) return
                if (request.resources.contains(PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID)) {
                    request.grant(request.resources)
                    return
                }
                val engineRequest = object : EnginePermissionRequest {
                    override val origin: Uri = request.origin
                    override val resources: Array<String> = request.resources
                    override fun grant(resources: Array<String>) = request.grant(resources)
                    override fun deny() = request.deny()
                }
                observer?.onPermissionRequest(engineRequest)
            }

            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message?
            ): Boolean {
                val ctx = view?.context
                if (ctx != null) {
                    val prefs = ctx.getSharedPreferences("HazePrefs", Context.MODE_PRIVATE)
                    val blockPopups = prefs.getBoolean(HazePrivacyShieldEngine.PREF_BLOCK_POPUPS, true)
                    val shieldActive = HazePrivacyShieldEngine.isShieldEnabled(ctx)
                    if (shieldActive && blockPopups && !isUserGesture && isDialog) {
                        // Suppress unsolicited background popup/popunder!
                        HazePrivacyShieldEngine.recordPopupBlocked(ctx, url, url, isPrivate)
                        return false
                    }
                }

                // 1. Check if user clicked an anchor/link element with target="_blank"
                val hitTest = view?.hitTestResult
                val hitUrl = hitTest?.extra
                if (!hitUrl.isNullOrEmpty() && (hitUrl.startsWith("http://") || hitUrl.startsWith("https://") || hitUrl.startsWith("haze://"))) {
                    return observer?.onNewWindowRequested(hitUrl, isUserGesture, isDialog) ?: false
                }

                // 2. Delegate to observer to route the window transport directly into a real tab
                if (resultMsg != null && observer?.onCreateWindow(resultMsg, isDialog, isUserGesture) == true) {
                    return true
                }
                return false
            }
        }

        webView.setDownloadListener { dlUrl, userAgent, contentDisposition, mimeType, contentLength ->
            observer?.onDownloadRequested(dlUrl, userAgent, contentDisposition, mimeType, contentLength)
        }
    }

    override fun setEngineObserver(observer: IEngineObserver) {
        this.observer = observer
    }

    override fun evaluateJavascript(script: String, callback: ((String?) -> Unit)?) {
        webView.evaluateJavascript(script, callback)
    }

    override fun findInPage(query: String, forward: Boolean) {
        webView.findAllAsync(query)
        if (forward) webView.findNext(true)
    }

    override fun findAllAsync(query: String) {
        webView.findAllAsync(query)
    }

    override fun findNext(forward: Boolean) {
        webView.findNext(forward)
    }

    override fun clearMatches() {
        webView.clearMatches()
    }

    override fun addJavascriptInterface(obj: Any, name: String) {
        webView.addJavascriptInterface(obj, name)
    }

    override fun removeJavascriptInterface(name: String) {
        webView.removeJavascriptInterface(name)
    }

    override fun setBackgroundColor(color: Int) {
        webView.setBackgroundColor(color)
    }

    override fun zoomIn(): Boolean = webView.zoomIn()

    override fun zoomOut(): Boolean = webView.zoomOut()

    override fun resetZoom() {
        webView.zoomBy(1.0f)
    }

    override fun captureThumbnail(callback: (Bitmap?) -> Unit) {
        try {
            val width = webView.width
            val height = webView.height
            if (width > 0 && height > 0) {
                val bitmap = Bitmap.createBitmap(width / 2, height / 2, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                canvas.scale(0.5f, 0.5f)
                webView.draw(canvas)
                callback(bitmap)
            } else {
                callback(null)
            }
        } catch (e: Exception) {
            callback(null)
        }
    }

    override fun onPause() {
        webView.onPause()
    }

    override fun onResume() {
        webView.onResume()
    }

    override fun destroy() {
        try {
            observer = null
            webView.stopLoading()
            webView.clearHistory()
            (webView.parent as? android.view.ViewGroup)?.removeView(webView)
            webView.destroy()
        } catch (_: Exception) {}
    }

    override fun setJavaScriptEnabled(enabled: Boolean) {
        webView.settings.javaScriptEnabled = enabled
    }

    override fun getCertificateInfo(): SslErrorInfo? {
        val cert = webView.certificate ?: return null
        return SslErrorInfo(
            primaryError = 0,
            url = url,
            certificateSummary = cert.toString(),
            issuedTo = cert.issuedTo?.dName ?: "Unknown",
            issuedBy = cert.issuedBy?.dName ?: "Unknown",
            validNotBefore = cert.validNotBeforeDate?.toString() ?: "",
            validNotAfter = cert.validNotAfterDate?.toString() ?: ""
        )
    }

    override fun clearCache(includeDiskFiles: Boolean) {
        webView.clearCache(includeDiskFiles)
    }

    override fun createPrintDocumentAdapter(documentName: String): Any? {
        return webView.createPrintDocumentAdapter(documentName)
    }

    override fun reload() {
        navigationController.reload()
    }

    override fun stopLoading() {
        navigationController.stopLoading()
    }

    override fun setFindListener(listener: ((activeMatchOrdinal: Int, numberOfMatches: Int, isDoneCounting: Boolean) -> Unit)?) {
        if (listener == null) {
            webView.setFindListener(null)
        } else {
            webView.setFindListener { activeMatchOrdinal, numberOfMatches, isDoneCounting ->
                listener(activeMatchOrdinal, numberOfMatches, isDoneCounting)
            }
        }
    }

    override fun saveWebArchive(filename: String, autoname: Boolean, callback: ((String?) -> Unit)?) {
        webView.saveWebArchive(filename, autoname) { path ->
            callback?.invoke(path)
        }
    }

    companion object {
        const val DESKTOP_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        const val SPOTIFY_DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        // A clean mobile UA that looks like Android Chrome — avoids WebView fingerprinting
        const val MOBILE_USER_AGENT = "Mozilla/5.0 (Linux; Android 11; Redmi Note 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

        val SPOTIFY_ENGINE_SCRIPT = """
            (function() {
                if (window.__hazeSpotifyEngineInitialized) return;
                window.__hazeSpotifyEngineInitialized = true;

                // 1. Cosmetic CSS: Remove banner ads, sponsored cards, upsell modals without breaking player
                try {
                    var s = document.getElementById('__haze_spotify_engine_css');
                    if (!s) {
                        s = document.createElement('style');
                        s.id = '__haze_spotify_engine_css';
                        s.type = 'text/css';
                        s.textContent = [
                            '[data-testid="ad-banner"], [data-testid="companion-ad"], [data-testid="leaderboard-ad"], [data-testid="billboard-ad"], [data-testid="hp-ad"], [data-testid="upgrade-button"], aside[aria-label*="Advertisement" i], div[aria-label*="Advertisement" i], aside[aria-label*="Anuncio" i], div[aria-label*="Anuncio" i], .ad-display, .sponsored-playlist, .main-leaderboard-container, .Root__top-bar--banner { display: none !important; height: 0 !important; max-height: 0 !important; opacity: 0 !important; pointer-events: none !important; }',
                            '[data-testid="tracklist-row"] button[data-testid="play-button"], [data-testid="compact-track-row"] button[data-testid="play-button"], [data-testid="tracklist-row"] [aria-label*="Play" i] { opacity: 1 !important; visibility: visible !important; display: inline-flex !important; pointer-events: auto !important; }',
                            '[data-testid="tracklist-row"], [data-testid="compact-track-row"] { cursor: pointer !important; min-height: 52px !important; }',
                            '.now-playing-bar-container, footer[data-testid="now-playing-bar"] { position: fixed !important; bottom: 0 !important; left: 0 !important; right: 0 !important; z-index: 99999 !important; }',
                            '[data-testid="upsell-modal"], [data-testid="login-modal"] { display: none !important; pointer-events: none !important; }'
                        ].join('\n');
                        (document.head || document.documentElement).appendChild(s);
                    }
                } catch(_) {}

                // 2. Audio playback & Ad supervisor: Mute commercial audio breaks and un-mute music smoothly
                function handleSpotifyAudio() {
                    try {
                        var isAd = !!document.querySelector('[data-testid="now-playing-widget"] [aria-label*="Advertisement" i], .Root__top-bar--banner, [data-testid="ad-banner"]');
                        var audio = document.querySelector('audio');
                        if (audio) {
                            if (isAd) {
                                audio.muted = true;
                                if (isFinite(audio.duration) && audio.duration > 0 && audio.currentTime < audio.duration) {
                                    audio.playbackRate = 16.0;
                                    audio.currentTime = audio.duration;
                                }
                            } else if (audio.muted && !audio.paused) {
                                audio.muted = false;
                                audio.playbackRate = 1.0;
                            }
                        }

                        // Auto-dismiss cookie consent banner if present
                        var cookieBtn = document.getElementById('onetrust-accept-btn-handler');
                        if (cookieBtn) cookieBtn.click();
                    } catch(_) {}
                }

                function monitorAudio(a) {
                    if (!a || a.__hazeAudioMonitored) return;
                    a.__hazeAudioMonitored = true;
                    a.addEventListener('play', handleSpotifyAudio, true);
                    a.addEventListener('playing', handleSpotifyAudio, true);
                    a.addEventListener('loadedmetadata', handleSpotifyAudio, true);
                }

                var existingAudio = document.querySelector('audio');
                if (existingAudio) monitorAudio(existingAudio);

                document.addEventListener('play', function(e) {
                    if (e.target && e.target.tagName === 'AUDIO') {
                        monitorAudio(e.target);
                        handleSpotifyAudio();
                    }
                }, true);

                handleSpotifyAudio();
                if (document.readyState === 'loading') {
                    document.addEventListener('DOMContentLoaded', handleSpotifyAudio);
                }
                window.addEventListener('load', handleSpotifyAudio);

                // Clean up any residual alternate floating player
                try {
                    var oldPlayer = document.getElementById('__haze_floating_player');
                    if (oldPlayer) oldPlayer.remove();
                } catch(_) {}
            })();
        """.trimIndent()

        val YOUTUBE_ENGINE_SCRIPT = """
            (function() {
                var host = window.location.hostname || '';
                var isYtMusic = host.indexOf('music.youtube.com') !== -1;
                if (window.__hazeYouTubeEngineInjected) return;
                window.__hazeYouTubeEngineInjected = true;

                // 1. Zero-overhead Cosmetic CSS Injection
                var ytCosmeticSelectors = isYtMusic ? [
                    'ytmusic-ad-slot-renderer',
                    'ytmusic-in-feed-ad-layout-renderer',
                    'ytmusic-display-ad-renderer',
                    'ytmusic-banner-promo-renderer',
                    'ytmusic-upsell-dialog-renderer',
                    'ytmusic-mealbar-promo-renderer',
                    'ytmusic-player-bar-promo-renderer',
                    '#player-ads'
                ] : [
                    'ytd-ad-slot-renderer',
                    'ytd-in-feed-ad-layout-renderer',
                    'ytd-promoted-sparkles-web-renderer',
                    'ytd-promoted-video-renderer',
                    'ytd-display-ad-renderer',
                    'ytd-statement-banner-renderer',
                    'ytd-banner-promo-renderer',
                    'ytd-compact-promoted-video-renderer',
                    'ytd-companion-ad-renderer',
                    'ytd-merch-shelf-renderer',
                    'ytd-action-companion-ad-renderer',
                    '#masthead-ad',
                    '#player-ads',
                    '.ytp-ad-overlay-container',
                    '.ytp-ad-text',
                    '.ytp-ad-message-container',
                    '.ytp-ad-image-overlay',
                    '.ytp-ad-overlay-image',
                    '.ytp-ad-player-overlay',
                    '.ytp-ad-player-overlay-layout',
                    '.ytp-ad-player-overlay-skip-or-preview',
                    '.ytp-ad-action-interstitial',
                    '.ytp-ad-survey',
                    '.ytp-ad-progress-list',
                    'ytm-promoted-sparkles-web-renderer',
                    'ytm-promoted-video-renderer',
                    'ytm-ad-slot-renderer',
                    'ytm-companion-ad-renderer',
                    'ytm-inline-ad',
                    'ytm-statement-banner-renderer',
                    'ytm-banner-promo-renderer',
                    'ytm-compact-promoted-video-renderer',
                    'ytm-promoted-sparkles-text-search-web-renderer',
                    'ytm-paid-content-overlay-renderer',
                    'ytm-mealbar-promo-renderer',
                    'yt-mealbar-promo-renderer',
                    'ytd-mealbar-promo-renderer',
                    'ytm-upsell-dialog-renderer',
                    'ytd-offline-promo-renderer',
                    '.badge-style-type-ad',
                    '.ad-badge',
                    '[aria-label*="Sponsored" i]',
                    '.ad-container',
                    '.ad-div'
                ];
                // Prevent Shorts hide-player offscreen displacement and poster overlay lock
                var shortsCarouselFixCss = ' .ytShortsCarouselHidePlayer .player-api, .ytShortsCarouselHidePlayerInViewport .player-api { transform: none !important; opacity: 1 !important; pointer-events: auto !important; } #player { transform: none !important; opacity: 1 !important; } ';
                var ytCosmeticCss = ytCosmeticSelectors.join(', ') + ' { display: none !important; opacity: 0 !important; visibility: hidden !important; pointer-events: none !important; height: 0 !important; max-height: 0 !important; min-height: 0 !important; margin: 0 !important; padding: 0 !important; border: 0 !important; }' + shortsCarouselFixCss;

                function injectYtStyle() {
                    try {
                        var style = document.getElementById('__haze_yt_style');
                        if (!style) {
                            style = document.createElement('style');
                            style.id = '__haze_yt_style';
                            style.type = 'text/css';
                            style.textContent = ytCosmeticCss;
                            var target = document.head || document.documentElement;
                            if (target) target.appendChild(style);
                        }
                    } catch(_) {}
                }
                injectYtStyle();
                if (document.readyState === 'loading') {
                    document.addEventListener('DOMContentLoaded', injectYtStyle);
                }

                // 2. Pre-sanitize Initial Payload & Ultra-fast Network Interception
                try {
                    function sanitizePlayerResponse(resp) {
                        try {
                            if (!resp) return resp;
                            if (resp.adPlacements) delete resp.adPlacements;
                            if (resp.adSlots) delete resp.adSlots;
                            if (resp.playerAds) delete resp.playerAds;
                            if (resp.playbackTracking) {
                                if (resp.playbackTracking.videostatsPlaybackUrl) delete resp.playbackTracking.videostatsPlaybackUrl;
                                if (resp.playbackTracking.videostatsDelayplayUrl) delete resp.playbackTracking.videostatsDelayplayUrl;
                            }
                        } catch(_) {}
                        return resp;
                    }

                    // Immediately strip pre-embedded ad placements from initial page payload
                    if (window.ytInitialPlayerResponse) {
                        sanitizePlayerResponse(window.ytInitialPlayerResponse);
                    }
                    try {
                        var _storedInitialPR = window.ytInitialPlayerResponse;
                        Object.defineProperty(window, 'ytInitialPlayerResponse', {
                            get: function() { return _storedInitialPR; },
                            set: function(val) {
                                sanitizePlayerResponse(val);
                                _storedInitialPR = val;
                            },
                            configurable: true,
                            enumerable: true
                        });
                    } catch(_) {}

                    var origFetch = window.fetch;
                    window.fetch = function(input, init) {
                        var url = (typeof input === 'string') ? input : (input && input.url) ? input.url : '';
                        if (url && (
                            url.indexOf('/api/stats/ads') !== -1 ||
                            url.indexOf('/api/stats/atr') !== -1 ||
                            url.indexOf('/pagead/') !== -1 ||
                            url.indexOf('/youtubei/v1/player/ad_break') !== -1 ||
                            url.indexOf('/ptracking') !== -1 ||
                            url.indexOf('/youtubei/v1/log_event') !== -1 ||
                            url.indexOf('/youtubei/v1/ads') !== -1 ||
                            url.indexOf('/get_midroll_info') !== -1
                        )) {
                            return Promise.resolve(new Response('', { status: 204, statusText: 'No Content' }));
                        }
                        var p = origFetch.apply(this, arguments);
                        if (url && (url.indexOf('/youtubei/v1/player') !== -1 || url.indexOf('/youtubei/v1/browse') !== -1)) {
                            return p.then(function(res) {
                                try {
                                    var cloned = res.clone();
                                    return cloned.json().then(function(data) {
                                        sanitizePlayerResponse(data);
                                        return new Response(JSON.stringify(data), {
                                            status: res.status,
                                            statusText: res.statusText,
                                            headers: res.headers
                                        });
                                    }).catch(function() { return res; });
                                } catch(_) { return res; }
                            });
                        }
                        return p;
                    };

                    var origXhrOpen = XMLHttpRequest.prototype.open;
                    XMLHttpRequest.prototype.open = function(method, url) {
                        var u = (url || '').toString();
                        if (u && (
                            u.indexOf('/api/stats/ads') !== -1 ||
                            u.indexOf('/api/stats/atr') !== -1 ||
                            u.indexOf('/pagead/') !== -1 ||
                            u.indexOf('/youtubei/v1/player/ad_break') !== -1 ||
                            u.indexOf('/ptracking') !== -1 ||
                            u.indexOf('/youtubei/v1/log_event') !== -1 ||
                            u.indexOf('/youtubei/v1/ads') !== -1 ||
                            u.indexOf('/get_midroll_info') !== -1
                        )) {
                            this.send = function() {};
                            return;
                        }
                        return origXhrOpen.apply(this, arguments);
                    };
                } catch(_) {}

                // Remove in-feed sponsored search cards and shelf items
                function removeInFeedSponsoredItems() {
                    try {
                        var sponsoredBadges = document.querySelectorAll('.badge-style-type-ad, .ad-badge, [aria-label*="Sponsored" i], ytd-ad-slot-renderer, ytm-ad-slot-renderer, ytm-promoted-sparkles-web-renderer');
                        for (var s = 0; s < sponsoredBadges.length; s++) {
                            var parentItem = sponsoredBadges[s].closest('ytm-video-with-context-renderer, ytm-compact-video-renderer, ytd-video-renderer, ytd-rich-item-renderer, ytm-item-section-renderer, ytd-item-section-renderer') || sponsoredBadges[s];
                            if (parentItem) {
                                parentItem.style.setProperty('display', 'none', 'important');
                            }
                        }
                    } catch(_) {}
                }
                removeInFeedSponsoredItems();
                document.addEventListener('yt-page-data-updated', removeInFeedSponsoredItems);

                try {
                    var setYtCfg = function() {
                        if (window.ytcfg && typeof window.ytcfg.set === 'function') {
                            window.ytcfg.set({
                                'WEB_EXPERIMENT_FLAGS': {
                                    'ad_block_banner_enabled': false,
                                    'ad_block_overlay_enabled': false,
                                    'disable_adblock_warning': true,
                                    'html5_ad_timeout_ms': 0,
                                    'html5_disable_ad_interruptions': true,
                                    'web_ad_frequency_cap_enabled': false
                                }
                            });
                        }
                    };
                    setYtCfg();
                    window.addEventListener('DOMContentLoaded', setYtCfg);
                } catch(_) {}

                // 3. Fast and Safe Instant YouTube Ad Neutralizer & Skip
                function skipAdSafely() {
                    try {
                        var player = document.getElementById('movie_player') || document.querySelector('.html5-video-player');
                        var adShowing = document.querySelector('.ad-showing, .ad-interrupting, .video-ads.ad-showing');

                        if (adShowing || (player && player.classList && (player.classList.contains('ad-showing') || player.classList.contains('ad-interrupting')))) {
                            // 1. YouTube native skipAd function
                            if (player && typeof player.skipAd === 'function') {
                                try { player.skipAd(); } catch(_) {}
                            }

                            // 2. Click skip buttons immediately
                            var skipBtn = document.querySelector(
                                '.ytp-ad-skip-button, .ytp-ad-skip-button-modern, .ytp-skip-ad-button, .ytm-skip-ad-button, ' +
                                'button.ytp-ad-skip-button, .ytp-ad-skip-button-slot button, [id*="ad-skip"], ' +
                                'button[class*="skip-button"], .videoAdUiSkipButton, .ytm-ad-overlay-close-button'
                            );
                            if (skipBtn) {
                                try { skipBtn.click(); } catch(_) {}
                            }

                            // 3. Instant advance to skip ad without black delay
                            var adVideo = document.querySelector('video.video-stream, .ad-showing video, .html5-main-video');
                            if (adVideo && isFinite(adVideo.duration) && adVideo.duration > 0) {
                                adVideo.muted = true;
                                if (adVideo.currentTime < adVideo.duration) {
                                    adVideo.currentTime = adVideo.duration;
                                }
                            }

                            // 4. Remove modal promo and upsell overlays
                            var enforcement = document.querySelectorAll(
                                'ytd-enforcement-message-view-model, #enforcement-message, yt-playability-error-supported-renderers, ' +
                                'ytm-mealbar-promo-renderer, ytm-upsell-dialog-renderer, ytd-mealbar-promo-renderer'
                            );
                            for (var i = 0; i < enforcement.length; i++) enforcement[i].remove();
                            var backdrops = document.querySelectorAll('tp-yt-iron-overlay-backdrop');
                            for (var b = 0; b < backdrops.length; b++) backdrops[b].remove();
                        }
                    } catch(_) {}
                }

                var skipScheduled = false;
                function scheduleSkipAd() {
                    if (skipScheduled) return;
                    skipScheduled = true;
                    requestAnimationFrame(function() {
                        skipScheduled = false;
                        skipAdSafely();
                    });
                }

                var observer = new MutationObserver(function(mutations) {
                    for (var i = 0; i < mutations.length; i++) {
                        var m = mutations[i];
                        if (m.type === 'attributes' && m.attributeName === 'class') {
                            var targetCls = (m.target && m.target.className) || '';
                            if (typeof targetCls === 'string' && (targetCls.indexOf('ad-showing') !== -1 || targetCls.indexOf('ad-interrupting') !== -1)) {
                                scheduleSkipAd();
                            }
                        }
                    }
                });

                var rootToObserve = document.getElementById('movie_player') || document.querySelector('.html5-video-player') || document.body;
                if (rootToObserve) {
                    observer.observe(rootToObserve, {
                        childList: false,
                        subtree: true,
                        attributes: true,
                        attributeFilter: ['class']
                    });
                } else {
                    document.addEventListener('DOMContentLoaded', function() {
                        var target = document.getElementById('movie_player') || document.querySelector('.html5-video-player') || document.body;
                        if (target) {
                            observer.observe(target, {
                                childList: false,
                                subtree: true,
                                attributes: true,
                                attributeFilter: ['class']
                            });
                        }
                    });
                }

                document.addEventListener('yt-navigate-finish', skipAdSafely);
                document.addEventListener('yt-page-data-updated', skipAdSafely);
                skipAdSafely();

                // 4. YouTube Shorts Smooth Auto-Play on Scroll & Snapping Fix
                (function initShortsAutoPlay() {
                    if (window.__hazeShortsScrollerActive) return;
                    window.__hazeShortsScrollerActive = true;

                    // Unhide Shorts player API and carousel container if concealed & preserve controls overlay
                    var cachedOverlayTemplate = null;
                    var cachedAvatarMap = {};

                    function getShortsAvatarMap() {
                        try {
                            var json = JSON.stringify(window.ytInitialData || {});
                            if (json && json.length > 50) {
                                var chunks = json.split('decoratedAvatarViewModel');
                                for (var i = 1; i < chunks.length; i++) {
                                    var chunk = chunks[i];
                                    var uMatch = chunk.match(/https:\/\/yt3\.ggpht\.com\/[^"\\\s]+/);
                                    var lMatch = chunk.match(/@([a-zA-Z0-9_\-\.]+)/);
                                    if (uMatch && lMatch) {
                                        cachedAvatarMap[lMatch[1].toLowerCase()] = uMatch[0];
                                    }
                                }
                            }
                        } catch(_) {}
                        return cachedAvatarMap;
                    }

                    function syncShortsMetadata(targetEl) {
                        try {
                            var root = targetEl || document;

                            var p = document.getElementById('movie_player');
                            var vData = (p && p.getVideoData) ? p.getVideoData() : {};
                            var pResp = (p && p.getPlayerResponse) ? p.getPlayerResponse() : {};
                            var micro = (pResp.microformat && pResp.microformat.playerMicroformatRenderer) || {};
                            var details = pResp.videoDetails || {};

                            var authorName = details.author || vData.author || '';
                            var videoId = details.videoId || vData.video_id || '';

                            // Determine real handle from current active video player
                            var handle = '';
                            if (micro.ownerProfileUrl) {
                                var parts = micro.ownerProfileUrl.split('/');
                                var lastPart = parts[parts.length - 1];
                                if (lastPart) handle = lastPart.startsWith('@') ? lastPart : ('@' + lastPart);
                            }
                            if (!handle && authorName) {
                                handle = '@' + authorName.toLowerCase().replace(/\s+/g, '');
                            }

                            // 1. Channel link & handle
                            var channelLink = root.querySelector('yt-reel-channel-bar-view-model a, .__haze_channel_bar span, .ytReelChannelBarViewModelChannelName a');
                            if (channelLink && handle) {
                                channelLink.innerText = handle;
                                if (channelLink.tagName === 'A') {
                                    channelLink.href = '/' + handle;
                                }
                            }

                            if (!authorName && !handle) return;
                            var cleanHandleKey = handle.replace(/^@+/, '').toLowerCase();

                            var title = details.title || vData.title || '';
                            var rawLikes = micro.likeCount ? parseInt(micro.likeCount, 10) : 0;
                            var likesFormatted = rawLikes >= 1000000 ? (rawLikes / 1000000).toFixed(1) + 'M' :
                                                 rawLikes >= 1000 ? (rawLikes / 1000).toFixed(rawLikes >= 10000 ? 0 : 1) + 'K' :
                                                 (rawLikes > 0 ? String(rawLikes) : 'Like');

                            // 2. Avatar Photo per Channel / Creator Account
                            var avatarShape = root.querySelector('.ytSpecAvatarShapeHost, .ytSpecAvatarShapeButton');
                            if (avatarShape) {
                                avatarShape.setAttribute('aria-label', 'Go to channel ' + handle);
                                avatarShape.style.position = 'relative';
                                avatarShape.style.width = '32px';
                                avatarShape.style.height = '32px';
                                avatarShape.style.setProperty('border-radius', '50%', 'important');
                                avatarShape.style.setProperty('overflow', 'hidden', 'important');

                                var imgContainer = avatarShape.querySelector('.ytSpecAvatarShapeAvatarSizeSmall, .ytSpecAvatarShapeAvatarSizeExtraSmall, .ytSpecAvatarShapeAvatarSizeMedium') || avatarShape.firstElementChild;
                                var img = avatarShape.querySelector('img');
                                var initial = avatarShape.querySelector('.__haze_avatar_initial');

                                // Check if author has changed
                                var lastAuthor = avatarShape.getAttribute('data-haze-author');
                                var authorChanged = lastAuthor && lastAuthor !== cleanHandleKey;
                                if (authorChanged) {
                                    avatarShape.removeAttribute('data-haze-synced-src');
                                    if (img) img.remove();
                                    img = null;
                                }
                                avatarShape.setAttribute('data-haze-author', cleanHandleKey);

                                // Find real avatar URL for this specific creator
                                var avatarMap = getShortsAvatarMap();
                                var photoUrl = avatarMap[cleanHandleKey] || '';

                                // Fallback: candidate images in active carousel item
                                if (!photoUrl) {
                                    var carouselItem = root.closest('ytm-reel-item-renderer, ytd-reel-video-renderer, .reel-video-in-sequence, ytm-shorts-element');
                                    var candidateImg = carouselItem ? carouselItem.querySelector('yt-reel-channel-bar-view-model img[src*="yt3.ggpht.com/"], img[src*="googleusercontent.com/"]') : null;
                                    if (candidateImg && candidateImg.src && candidateImg.src.indexOf('data:') === -1 && candidateImg !== img) {
                                        photoUrl = candidateImg.src;
                                    }
                                }

                                if (photoUrl) {
                                    avatarShape.setAttribute('data-haze-synced-src', photoUrl);
                                    if (initial) initial.remove();
                                    if (imgContainer) imgContainer.style.removeProperty('display');
                                    if (!img) {
                                        img = document.createElement('img');
                                        img.className = 'ytCoreImageHost ytSpecAvatarShapeImage';
                                        avatarShape.appendChild(img);
                                    }
                                    if (img.getAttribute('src') !== photoUrl) {
                                        img.setAttribute('src', photoUrl);
                                    }
                                    img.style.setProperty('display', 'block', 'important');
                                    img.style.setProperty('visibility', 'visible', 'important');
                                    img.style.setProperty('opacity', '1', 'important');
                                    img.style.setProperty('width', '32px', 'important');
                                    img.style.setProperty('height', '32px', 'important');
                                    img.style.setProperty('border-radius', '50%', 'important');
                                    img.style.setProperty('object-fit', 'cover', 'important');
                                    img.style.setProperty('position', 'relative', 'important');
                                    img.style.setProperty('z-index', '999', 'important');

                                    var overlays = avatarShape.querySelectorAll('.ytSpecAvatarShapeImageOverlays');
                                    for (var o = 0; o < overlays.length; o++) overlays[o].style.display = 'none';
                                } else {
                                    // Creator initials avatar fallback with unique color hash
                                    if (img) img.remove();
                                    if (imgContainer) imgContainer.style.setProperty('display', 'none', 'important');
                                    if (!initial) {
                                        initial = document.createElement('div');
                                        initial.className = '__haze_avatar_initial';
                                        avatarShape.appendChild(initial);
                                    }
                                    var colors = ['#E50914', '#1E88E5', '#43A047', '#FB8C00', '#8E24AA', '#00ACC1', '#D81B60', '#5E35B1', '#00897B'];
                                    var hash = 0;
                                    for (var c = 0; c < cleanHandleKey.length; c++) hash = (hash << 5) - hash + cleanHandleKey.charCodeAt(c);
                                    var chosenBg = colors[Math.abs(hash) % colors.length];

                                    initial.setAttribute('style', 'position:absolute !important; top:0 !important; left:0 !important; width:32px !important; height:32px !important; border-radius:50% !important; background:' + chosenBg + ' !important; color:#ffffff !important; display:flex !important; align-items:center !important; justify-content:center !important; font-size:15px !important; font-weight:bold !important; z-index:9999 !important; margin:0 !important; padding:0 !important;');
                                    initial.innerText = (authorName || cleanHandleKey).charAt(0).toUpperCase();
                                }
                            }

                            // 3. Subscribe button
                            var subBtn = root.querySelector('yt-reel-channel-bar-view-model button, .__haze_channel_bar button');
                            if (subBtn) {
                                subBtn.setAttribute('aria-label', 'Subscribe to ' + handle + '.');
                                var subText = subBtn.querySelector('.ytSpecButtonShapeNextButtonTextContent') || subBtn;
                                if (subBtn.getAttribute('data-haze-subscribed') === 'true') {
                                    subText.innerText = 'Subscribed';
                                    subBtn.style.background = 'rgba(255,255,255,0.2)';
                                    subBtn.style.color = '#fff';
                                } else {
                                    subText.innerText = 'Subscribe';
                                    subBtn.style.background = '#fff';
                                    subBtn.style.color = '#0f0f0f';
                                }
                                if (!subBtn.__hazeSubBound) {
                                    subBtn.__hazeSubBound = true;
                                    subBtn.addEventListener('click', function(e) {
                                        e.stopPropagation();
                                        var isSub = subBtn.getAttribute('data-haze-subscribed') === 'true';
                                        if (isSub) {
                                            subBtn.removeAttribute('data-haze-subscribed');
                                            subText.innerText = 'Subscribe';
                                            subBtn.style.background = '#fff';
                                            subBtn.style.color = '#0f0f0f';
                                        } else {
                                            subBtn.setAttribute('data-haze-subscribed', 'true');
                                            subText.innerText = 'Subscribed';
                                            subBtn.style.background = 'rgba(255,255,255,0.2)';
                                            subBtn.style.color = '#fff';
                                        }
                                    });
                                }
                            }

                            // 4. Video Title
                            var titleEl = root.querySelector('.ytShortsVideoTitleViewModelShortsVideoTitle, yt-reel-metapanel-view-model h1, .ytReelMetapanelViewModelMetapanelItemTitle');
                            if (titleEl) {
                                titleEl.innerText = title;
                                titleEl.setAttribute('aria-label', title);
                            }

                            // 5. Hide search suggestion pill if stale
                            var searchPill = root.querySelector('yt-shorts-suggested-action-view-model');
                            if (searchPill) {
                                searchPill.style.display = 'none';
                            }

                            // 6. Sound / audio pill in metapanel
                            var audioPill = root.querySelector('a.ytSpecButtonShapeNextHost[href*="watch?v="], a.ytSpecButtonShapeNextHost[href*="/shorts/"]');
                            if (audioPill) {
                                var audioSpan = audioPill.querySelector('.ytSpecButtonShapeNextButtonTextContent span span') || 
                                                audioPill.querySelector('.ytSpecButtonShapeNextButtonTextContent span') ||
                                                audioPill.querySelector('.ytSpecButtonShapeNextButtonTextContent');
                                if (audioSpan) {
                                    audioSpan.innerText = 'Original audio - ' + authorName;
                                }
                                audioPill.setAttribute('aria-label', 'Original audio - ' + authorName);
                                var iconSpan = audioPill.querySelector('.yt-icon-shape');
                                if (iconSpan) iconSpan.innerText = '';
                            }

                            // 7. Like count
                            var likeBtn = root.querySelector('button[aria-label*="like" i]');
                            if (likeBtn) {
                                likeBtn.setAttribute('aria-label', 'like this video along with ' + (rawLikes || 'others'));
                                var likeCountSpan = (likeBtn.parentElement && likeBtn.parentElement.querySelector('.ytSpecButtonShapeWithLabelLabel span')) ||
                                                    (likeBtn.parentElement && likeBtn.parentElement.querySelector('.ytAttributedStringHost'));
                                if (likeCountSpan && rawLikes > 0) {
                                    likeCountSpan.innerText = likesFormatted;
                                }
                            }

                            // 8. Sound pivot button at bottom-right
                            var soundPivot = root.querySelector('a[aria-label*="See more videos using this sound"], div.reel-player-overlay-actions a:last-child');
                            if (soundPivot) {
                                if (videoId) {
                                    soundPivot.href = 'https://m.youtube.com/source/' + videoId + '/shorts';
                                }
                                var pivotImg = soundPivot.querySelector('img');
                                if (pivotImg) {
                                    pivotImg.style.display = 'none';
                                }
                                var pivotInitial = soundPivot.querySelector('.__haze_pivot_initial');
                                if (!pivotInitial) {
                                    pivotInitial = document.createElement('div');
                                    pivotInitial.className = '__haze_pivot_initial';
                                    soundPivot.appendChild(pivotInitial);
                                }
                                pivotInitial.style.cssText = 'width:36px; height:36px; border-radius:6px; background:#212121; border:1px solid rgba(255,255,255,0.4); display:flex; align-items:center; justify-content:center; color:#fff; font-size:16px;';
                                pivotInitial.innerHTML = '<svg xmlns=\"http://www.w3.org/2000/svg\" height=\"20\" viewBox=\"0 0 24 24\" width=\"20\" fill=\"#fff\"><path d=\"M12 3v10.55c-.59-.34-1.27-.55-2-.55-2.21 0-4 1.79-4 4s1.79 4 4 4 4-1.79 4-4V7h4V3h-6z\"/></svg>';
                            }
                        } catch(_) {}
                    }

                    function preserveShortsOverlay() {
                        try {
                            var allOverlays = Array.from(document.querySelectorAll('ytm-reel-player-overlay-renderer'));
                            var nativeOverlay = allOverlays.find(function(o) { return !o.__hazeInjected; });
                            var injectedOverlay = allOverlays.find(function(o) { return o.__hazeInjected; });

                            if (nativeOverlay && nativeOverlay.innerHTML.length > 500) {
                                cachedOverlayTemplate = nativeOverlay.cloneNode(true);
                                if (injectedOverlay) {
                                    injectedOverlay.remove();
                                }
                                syncShortsMetadata(nativeOverlay);
                                return;
                            }

                            var tc = document.querySelector('.layer.touch-controls');
                            // Only inject if there is NO native overlay attached in touch-controls
                            if (tc && !tc.querySelector('ytm-reel-player-overlay-renderer:not([data-haze-injected])') && cachedOverlayTemplate) {
                                if (injectedOverlay) {
                                    syncShortsMetadata(injectedOverlay);
                                    return;
                                }
                                var clone = cachedOverlayTemplate.cloneNode(true);
                                clone.__hazeInjected = true;
                                clone.setAttribute('data-haze-injected', 'true');
                                tc.appendChild(clone);

                                // Ensure channel bar and subscribe button are present
                                var metaContainer = clone.querySelector('.ytReelPlayerOverlayViewModelHorizontalContainer');
                                var existingChannelBar = clone.querySelector('.__haze_channel_bar, yt-reel-channel-bar-view-model');
                                if (!existingChannelBar && metaContainer) {
                                    var channelBar = document.createElement('div');
                                    channelBar.className = '__haze_channel_bar';
                                    channelBar.style.cssText = 'display:flex; align-items:center; gap:8px; margin-bottom:8px; pointer-events:auto;';

                                    var avatar = document.createElement('div');
                                    avatar.style.cssText = 'width:32px; height:32px; min-width:32px; border-radius:50%; background:#ff0033; color:#fff; display:flex; align-items:center; justify-content:center; font-weight:bold; font-size:14px;';
                                    avatar.innerText = 'Y';

                                    var authorName = document.createElement('span');
                                    authorName.style.cssText = 'color:#fff; font-weight:bold; font-size:14px; text-shadow:0 1px 2px rgba(0,0,0,0.8);';
                                    authorName.innerText = '@creator';

                                    var subBtn = document.createElement('button');
                                    subBtn.style.cssText = 'background:#fff; color:#0f0f0f; border:none; border-radius:18px; padding:6px 14px; font-weight:bold; font-size:12px; cursor:pointer; box-shadow:0 1px 3px rgba(0,0,0,0.3);';
                                    subBtn.innerText = 'Subscribe';

                                    channelBar.appendChild(avatar);
                                    channelBar.appendChild(authorName);
                                    channelBar.appendChild(subBtn);

                                    var metapanel = clone.querySelector('.ytReelPlayerOverlayViewModelMetadataContainerMetapanel');
                                    if (metapanel) {
                                        metapanel.insertBefore(channelBar, metapanel.firstChild);
                                    }
                                }

                                syncShortsMetadata(clone);
                            }
                        } catch(_) {}
                    }

                    function ensurePlayerVisible() {
                        try {
                            preserveShortsOverlay();
                            var wrapper = document.getElementById('carousel-scrollable-wrapper');
                            if (wrapper) {
                                wrapper.classList.remove('ytShortsCarouselHidePlayer');
                                wrapper.classList.remove('ytShortsCarouselHidePlayerInViewport');
                            }
                            var player = document.getElementById('player');
                            if (player) {
                                if (player.style.transform && player.style.transform.indexOf('-9999') !== -1) {
                                    player.style.setProperty('transform', 'none', 'important');
                                }
                                if (player.style.opacity === '0') {
                                    player.style.setProperty('opacity', '1', 'important');
                                }
                            }
                        } catch(_) {}
                    }

                    // Swipe-to-Next-Video Bridge for Shorts Feed
                    var startY = 0;
                    var startX = 0;
                    var startTime = 0;
                    window.addEventListener('touchstart', function(e) {
                        if (e.touches && e.touches.length === 1) {
                            startY = e.touches[0].clientY;
                            startX = e.touches[0].clientX;
                            startTime = Date.now();
                        }
                    }, { capture: true, passive: true });

                    window.addEventListener('touchend', function(e) {
                        if (!e.changedTouches || e.changedTouches.length === 0) return;
                        var endY = e.changedTouches[0].clientY;
                        var endX = e.changedTouches[0].clientX;
                        var dy = endY - startY;
                        var dx = endX - startX;
                        var dt = Date.now() - startTime;
                        // Vertical swipe detection
                        if (Math.abs(dy) > 40 && Math.abs(dy) > Math.abs(dx) * 1.2 && dt < 800) {
                            var isNext = dy < 0; // Swipe up = next video; Swipe down = previous video
                            var a11yButtons = Array.from(document.querySelectorAll('.hidden-a11y-nav button'));
                            var targetBtn = a11yButtons.find(function(b) {
                                var label = (b.getAttribute('aria-label') || '').toLowerCase();
                                return isNext ? (label.indexOf('next') !== -1) : (label.indexOf('prev') !== -1);
                            });

                            if (targetBtn && !targetBtn.disabled) {
                                targetBtn.click();
                            } else {
                                var keyCode = isNext ? 40 : 38;
                                var keyName = isNext ? 'ArrowDown' : 'ArrowUp';
                                var ev = new KeyboardEvent('keydown', {
                                    key: keyName,
                                    code: keyName,
                                    keyCode: keyCode,
                                    which: keyCode,
                                    bubbles: true,
                                    cancelable: true
                                });
                                window.dispatchEvent(ev);
                            }
                            setTimeout(ensurePlayerVisible, 100);
                            setTimeout(ensurePlayerVisible, 400);
                        }
                    }, { capture: true, passive: true });

                    function autoPlayActiveShort() {
                        try {
                            ensurePlayerVisible();
                            var videos = document.querySelectorAll('video');
                            if (!videos || videos.length === 0) return;
                            var midY = window.innerHeight / 2;
                            var bestVideo = null;
                            var minDistance = 999999;

                            for (var i = 0; i < videos.length; i++) {
                                var v = videos[i];
                                var rect = v.getBoundingClientRect();
                                if (rect.height > 60 && rect.width > 60) {
                                    var vMid = rect.top + (rect.height / 2);
                                    var dist = Math.abs(vMid - midY);
                                    if (dist < minDistance) {
                                        minDistance = dist;
                                        bestVideo = v;
                                    }
                                }
                            }

                            // Only pause video if it is definitively scrolled far out of the viewport
                            for (var j = 0; j < videos.length; j++) {
                                var vid = videos[j];
                                if (vid !== bestVideo) {
                                    var r = vid.getBoundingClientRect();
                                    if (r.bottom < -150 || r.top > (window.innerHeight + 150)) {
                                        if (!vid.paused) {
                                            try { vid.pause(); } catch(_) {}
                                        }
                                    }
                                }
                            }

                            if (bestVideo && minDistance < (window.innerHeight * 0.48)) {
                                if (bestVideo.paused) {
                                    var playPromise = bestVideo.play();
                                    if (playPromise !== undefined) {
                                        playPromise.catch(function() {
                                            // Fallback attempt without forcefully breaking audio pipeline
                                            bestVideo.play().catch(function() {});
                                        });
                                    }
                                }

                                // Trigger YouTube Shorts play buttons if visible
                                try {
                                    var container = bestVideo.closest('ytm-reel-item-renderer, ytd-reel-video-renderer, .reel-video-in-sequence, ytm-shorts-element') || bestVideo.parentElement;
                                    if (container) {
                                        var playBtn = container.querySelector(
                                            'button.player-control-play-pause-icon, button[aria-label*="Play" i], .ytp-large-play-button, .ytp-play-button, div[aria-label="Play" i]'
                                        );
                                        if (playBtn && playBtn.offsetParent !== null) {
                                            playBtn.click();
                                        }
                                    }
                                } catch(_) {}
                            }
                        } catch (_) {}
                    }

                    // IntersectionObserver for instantaneous detection as containers scroll
                    try {
                        if (window.IntersectionObserver) {
                            var shortsObserver = new IntersectionObserver(function(entries) {
                                autoPlayActiveShort();
                            }, {
                                threshold: [0.3, 0.5, 0.75]
                            });

                            function observeAllVideos() {
                                var vids = document.querySelectorAll('video, ytm-reel-item-renderer, ytd-reel-video-renderer');
                                for (var k = 0; k < vids.length; k++) {
                                    if (!vids[k].__hazeObserved) {
                                        vids[k].__hazeObserved = true;
                                        shortsObserver.observe(vids[k]);
                                    }
                                }
                            }
                            observeAllVideos();

                            var mutObserver = new MutationObserver(function() {
                                observeAllVideos();
                            });
                            mutObserver.observe(document.body || document.documentElement, {
                                childList: true,
                                subtree: true
                            });
                        }
                    } catch(_) {}

                    window.addEventListener('scroll', autoPlayActiveShort, { capture: true, passive: true });
                    document.addEventListener('scroll', autoPlayActiveShort, { capture: true, passive: true });
                    document.addEventListener('touchmove', autoPlayActiveShort, { passive: true });
                    document.addEventListener('touchend', function() {
                        setTimeout(autoPlayActiveShort, 50);
                        setTimeout(autoPlayActiveShort, 200);
                        setTimeout(autoPlayActiveShort, 500);
                    }, { passive: true });
                    document.addEventListener('yt-navigate-finish', function() {
                        setTimeout(ensurePlayerVisible, 100);
                        setTimeout(ensurePlayerVisible, 300);
                        setTimeout(autoPlayActiveShort, 350);
                    });
                    document.addEventListener('yt-page-data-updated', function() {
                        setTimeout(ensurePlayerVisible, 100);
                        setTimeout(ensurePlayerVisible, 300);
                        setTimeout(autoPlayActiveShort, 350);
                    });
                    setInterval(autoPlayActiveShort, 600);
                    setInterval(ensurePlayerVisible, 800);
                    window.__hazeCheckShortsAutoplay = autoPlayActiveShort;
                })();

                // 5. Universal Instant-Play On User Click for all Videos
                (function initInstantVideoPlayback() {
                    function triggerPlayImmediately(target) {
                        try {
                            if (!target) return;
                            // 1. If clicking directly on or near a video element or player container
                            var video = (target.tagName === 'VIDEO') ? target : target.querySelector('video') || (target.closest ? target.closest('.html5-video-player, #movie_player, ytm-video-with-context-renderer, ytm-reel-item-renderer, ytd-reel-video-renderer, .player-container, .video-stream')?.querySelector('video') : null);
                            if (video && video.paused) {
                                var pr = video.play();
                                if (pr !== undefined) pr.catch(function() {});
                            }

                            // 2. Click any hidden or delayed play button inside player
                            var playBtn = target.closest ? target.closest('.html5-video-player, #movie_player, .player-container, ytm-reel-item-renderer, ytd-reel-video-renderer')?.querySelector('.ytp-large-play-button, .ytp-play-button, button.player-control-play-pause-icon, button[aria-label*="Play" i], div[aria-label="Play" i]') : null;
                            if (playBtn && playBtn !== target) {
                                playBtn.click();
                            }
                        } catch(_) {}
                    }

                    document.addEventListener('click', function(e) {
                        if (typeof skipAdSafely === 'function') skipAdSafely();
                        triggerPlayImmediately(e.target);
                    }, { capture: true, passive: true });

                    document.addEventListener('touchend', function(e) {
                        if (e.target) {
                            setTimeout(function() {
                                if (typeof skipAdSafely === 'function') skipAdSafely();
                                triggerPlayImmediately(e.target);
                            }, 50);
                            setTimeout(function() {
                                if (typeof skipAdSafely === 'function') skipAdSafely();
                                triggerPlayImmediately(e.target);
                            }, 200);
                        }
                    }, { passive: true });
                })();
            })();
        """.trimIndent()

        val SOCIAL_SHORT_VIDEO_PROFILE_SCRIPT = """
            (function() {
                if (window.__hazeSocialProfileScript) return;
                var host = window.location.hostname || '';
                if (!/(youtube|instagram|facebook|tiktok)\./i.test(host)) return;
                window.__hazeSocialProfileScript = true;

                function isSquareAvatar(img) {
                    if (!img || !img.src || img.src.indexOf('data:') === 0) return false;
                    var w = img.width || img.naturalWidth;
                    var h = img.height || img.naturalHeight;
                    return w > 0 && h > 0 && Math.abs(w - h) <= Math.max(w, h) * 0.2 && w <= 256 && h <= 256;
                }

                function findAvatar(video) {
                    var node = video;
                    for (var depth = 0; node && depth < 7; depth++, node = node.parentElement) {
                        var images = node.querySelectorAll ? node.querySelectorAll('img[src]') : [];
                        for (var i = 0; i < images.length; i++) {
                            if (isSquareAvatar(images[i]) && images[i] !== video) return images[i];
                        }
                    }
                    return null;
                }

                function syncVideo(video) {
                    if (!video || video.tagName !== 'VIDEO') return;
                    var avatar = findAvatar(video);
                    if (!avatar) return;
                    var parent = video.parentElement;
                    if (!parent) return;
                    var overlay = parent.querySelector(':scope > .__haze_creator_avatar');
                    if (!overlay) {
                        if (getComputedStyle(parent).position === 'static') parent.style.position = 'relative';
                        overlay = document.createElement('img');
                        overlay.className = '__haze_creator_avatar';
                        overlay.alt = 'Creator profile';
                        overlay.setAttribute('aria-hidden', 'true');
                        overlay.style.cssText = 'position:absolute;left:12px;top:12px;width:36px;height:36px;border-radius:50%;object-fit:cover;z-index:2147483000;pointer-events:none;box-shadow:0 1px 4px rgba(0,0,0,.5);';
                        parent.appendChild(overlay);
                    }
                    if (overlay.src !== avatar.currentSrc && overlay.src !== avatar.src) {
                        overlay.src = avatar.currentSrc || avatar.src;
                    }
                }

                function scan() {
                    try {
                        var videos = document.querySelectorAll('video');
                        for (var i = 0; i < videos.length; i++) syncVideo(videos[i]);
                    } catch(_) {}
                }
                document.addEventListener('play', function(e) { syncVideo(e.target); }, true);
                document.addEventListener('loadedmetadata', function(e) { syncVideo(e.target); }, true);
                window.addEventListener('scroll', scan, {passive:true});
                if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', scan);
                var observer = new MutationObserver(scan);
                observer.observe(document.documentElement || document, {childList:true, subtree:true});
                setInterval(scan, 1200);
            })();
        """.trimIndent()

        val ADGUARD_UNIVERSAL_SHIELD_SCRIPT = """
            (function() {
                var host = window.location.hostname || '';
                var href = window.location.href || '';
                if (host.indexOf('youtube.com') !== -1 || href.indexOf('youtube.com') !== -1 || host.indexOf('spotify.com') !== -1 || href.indexOf('spotify.com') !== -1) return;
                if (window.__hazeAdGuardShieldInjected) return;
                window.__hazeAdGuardShieldInjected = true;

                var css = [
                    'ins.adsbygoogle, .adsbygoogle, iframe[id*="google_ads"], iframe[src*="doubleclick.net"], iframe[src*="googlesyndication.com"], iframe[src*="googleadservices.com"], iframe[src*="amazon-adsystem.com"], iframe[src*="adnxs.com"], iframe[src*="criteo.com"], iframe[src*="pubmatic.com"], iframe[src*="openx.net"], iframe[src*="rubiconproject.com"], iframe[src*="smartadserver.com"], iframe[src*="teads.tv"], iframe[src*="adform.net"], div[id*="google_ads"], div[id^="dfp-"], div[id^="gpt-"], div[id*="gpt-ad"], div[id^="div-gpt-ad"], div.ad-slot, div.advertisement, div.advertisement-container, div.advertisement-wrapper, div.advertisement-banner, div.advertisement-slot, div.advertisement-box, div.advertisement-section, div.advertisement-placeholder, section.advertisement, section.ad-container, section.ad-slot, aside.advertisement, aside.ad-container, aside.ad-slot, .ad-banner, .banner-ad, .banner_ad, .ad-slot, .ad-box, .ad-label, .ad-notice, .ad-title, .ad-header, .ad-caption, .advertisement-label, .ad-badge, .sponsored-label, .sponsored-tag, .advertisement, [data-ad-unit], [data-ad-slot], [data-ad-client], [data-ad-name], [data-ad-format], [data-ad-container], [data-ad-wrapper], [data-ad-placeholder], [data-google-query-id], [aria-label="Advertisement" i], [aria-label="Sponsored" i], [aria-label="Advert" i], [aria-label="Anuncio" i], [data-advertisement], .sponsored-post, .sponsored-content, .promoted-content, .sponsored-badge, .sponsored-wrapper, .sponsored-container, .header-ad, .footer-ad, .sidebar-ad, .top-ad, .bottom-ad, .inline-ad, .in-article-ad, .in-content-ad, .native-ad, .native_ad, .sticky-ad, .floating-ad, .tbl-feed-container, .trc_rbox_div, div[id*="taboola"], [data-taboola-placeholder], .outbrain_widget, .OB-widget, div[id*="rc-widget"], .mgbox, .mgid-widget, [data-revcontent], [data-mgid], .ntv-ad, div[id*="nativo"], .ima-ad-container, .video-ad-overlay, .vjs-ad-loading, .jw-flag-ads, .jw-ad-container',
                    '[class*="paywall"], [id*="paywall"], [class*="gate-overlay"], [id*="gate-overlay"], [class*="subscriber-gate"], [class*="subscription-barrier"], [class*="metered-barrier"], [class*="piano-modal"], [class*="tinypass"], [id*="tinypass"], .paywall-container, #paywall-container, .gateway-content, .subscriber-only, [class*="newsletter-modal"], [class*="email-gate"], [class*="newsletter-signup-overlay"], [class*="newsletter-popup"], [class*="subscribe-modal"], div[class*="paywall-overlay"], div[id*="paywall-overlay"], .tp-modal, .tp-backdrop, .cleanslate, #reg-wall, .reg-wall, div[class*="wall-content"], div[id*="wall-content"], .sp_message_container_root, [id^="sp_message_container"], .fc-consent-root, .fc-ab-root, .adblock-overlay, [class*="adblock-modal"], [id*="adblock-overlay"], .adblocker-overlay, [id*="adblock-detector"], #onetrust-consent-sdk, .cc-window, #didomi-host, .cookie-banner, [id*="cookie-notice"], .qc-cmp2-container, #CybotCookiebotDialog, div[class*="cookie-banner"], div[id*="cookie-banner"], div[class*="cookie-consent"], div[id*="cookie-consent"], .interstitial-ad, div[class*="interstitial"], div[id*="interstitial"]',
                    '{ display: none !important; height: 0 !important; max-height: 0 !important; min-height: 0 !important; width: 0 !important; max-width: 0 !important; min-width: 0 !important; margin: 0 !important; padding: 0 !important; border: none !important; outline: none !important; opacity: 0 !important; pointer-events: none !important; overflow: hidden !important; clip: rect(0, 0, 0, 0) !important; }',
                    'article, main, .content, .article-body, .story-body { filter: none !important; -webkit-filter: none !important; user-select: auto !important; }',
                    'video, video.video-stream, video.html5-main-video, .html5-main-video, [class*="video-stream"] video, [class*="player-container"] video { opacity: 1 !important; visibility: visible !important; max-height: none !important; max-width: none !important; pointer-events: auto !important; }'
                ].join('\n');

                function injectStyle() {
                    try {
                        var style = document.getElementById('__haze_adguard_style');
                        if (!style) {
                            style = document.createElement('style');
                            style.id = '__haze_adguard_style';
                            style.type = 'text/css';
                            style.textContent = css;
                            (document.head || document.documentElement).appendChild(style);
                        }
                    } catch(_) {}
                }
                injectStyle();
                if (document.readyState === 'loading') {
                    document.addEventListener('DOMContentLoaded', injectStyle);
                }

                try {
                    var origOpen = window.open;
                    window.open = function(url) {
                        var u = (url || '').toString().toLowerCase();
                        if (u && (u.indexOf('popads') !== -1 || u.indexOf('popcash') !== -1 || u.indexOf('propeller') !== -1 || u.indexOf('adsterra') !== -1 || u.indexOf('exoclick') !== -1 || u.indexOf('hilltopads') !== -1 || u.indexOf('clickadu') !== -1 || u.indexOf('adcash') !== -1 || u.indexOf('popunder') !== -1 || u.indexOf('clickunder') !== -1)) {
                            return null;
                        }
                        return origOpen.apply(this, arguments);
                    };

                    if (window.Notification && window.Notification.requestPermission) {
                        window.Notification.requestPermission = function(cb) {
                            var p = Promise.resolve('denied');
                            if (typeof cb === 'function') cb('denied');
                            return p;
                        };
                    }

                    window.canRunAds = true;
                    window.isAdBlockActive = false;
                    window.adblock = false;
                } catch(_) {}

                function cleanOverlays() {
                    try {
                        var docEl = document.documentElement;
                        var body = document.body;
                        // Avoid breaking SPAs with strict overflow requirements like YouTube Music
                        if (host.indexOf('youtube.com') === -1 && host.indexOf('spotify.com') === -1) {
                            if (docEl && getComputedStyle(docEl).overflow === 'hidden') {
                                docEl.style.setProperty('overflow', 'auto', 'important');
                            }
                            if (body && getComputedStyle(body).overflow === 'hidden') {
                                body.style.setProperty('overflow', 'auto', 'important');
                            }
                        }
                        var blurs = document.querySelectorAll('[style*="blur"], [style*="filter"]');
                        for (var b = 0; b < blurs.length; b++) {
                            var s = blurs[b].style;
                            if (s.filter && s.filter.indexOf('blur') !== -1) s.setProperty('filter', 'none', 'important');
                            if (s.webkitFilter && s.webkitFilter.indexOf('blur') !== -1) s.setProperty('-webkit-filter', 'none', 'important');
                        }

                        // Clean leftover empty ad containers and lonely "Advertisement" / "Sponsored" notices
                        var adNotices = document.querySelectorAll('p, span, div, center');
                        for (var n = 0; n < adNotices.length; n++) {
                            var el = adNotices[n];
                            if (el.children.length === 0) {
                                var txt = (el.innerText || '').trim().toLowerCase();
                                if (txt === 'advertisement' || txt === 'sponsored' || txt === 'promoted' || txt === 'anuncio' || txt === 'advertentie' || txt === 'anzeige') {
                                    var parentBox = el.closest('.code-block, [class*="ad-"], [class*="banner"], .elementor-widget-container, .elementor-shortcode, center, div, section');
                                    if (parentBox && (parentBox.innerText.trim().toLowerCase() === txt || parentBox.offsetHeight < 60)) {
                                        parentBox.style.setProperty('display', 'none', 'important');
                                    }
                                }
                            }
                        }
                    } catch(_) {}
                }
                if (document.body) cleanOverlays();
                else window.addEventListener('DOMContentLoaded', cleanOverlays);
                setInterval(cleanOverlays, 1500);

                // Universal In-Feed & Short-form Video Autoplay Engine (Reels, TikTok, Shorts, Twitter, Reddit, Web feeds)
                (function initUniversalVideoAutoPlay() {
                    if (window.__hazeUniversalAutoplayActive) return;
                    window.__hazeUniversalAutoplayActive = true;

                    function autoPlayVisibleVideo() {
                        try {
                            var vids = document.querySelectorAll('video');
                            if (!vids || vids.length === 0) return;
                            var mid = window.innerHeight / 2;
                            var activeVid = null;
                            var closest = 999999;

                            for (var i = 0; i < vids.length; i++) {
                                var vid = vids[i];
                                var r = vid.getBoundingClientRect();
                                if (r.height > 60 && r.width > 60) {
                                    var vCenter = r.top + (r.height / 2);
                                    var d = Math.abs(vCenter - mid);
                                    if (d < closest) {
                                        closest = d;
                                        activeVid = vid;
                                    }
                                }
                            }

                            // Pause videos that are scrolled completely out of view (grace buffer 150px)
                            for (var j = 0; j < vids.length; j++) {
                                var v = vids[j];
                                if (v !== activeVid) {
                                    var vr = v.getBoundingClientRect();
                                    if (vr.bottom < -150 || vr.top > (window.innerHeight + 150)) {
                                        if (!v.paused) {
                                            try { v.pause(); } catch(_) {}
                                        }
                                    }
                                }
                            }

                            // Play the video closest to viewport center
                            if (activeVid && closest < (window.innerHeight * 0.48)) {
                                if (activeVid.paused) {
                                    var p = activeVid.play();
                                    if (p !== undefined) {
                                        p.catch(function() {
                                            // Retry without force-muting unless needed
                                            activeVid.play().catch(function() {});
                                        });
                                    }
                                }

                                // Handle custom play button overlays (Instagram, Facebook, TikTok, Twitter/X, Reddit)
                                try {
                                    var parent = activeVid.closest('div[role="dialog"], div[role="article"], article, section, div[class*="player"], div[class*="video"]') || activeVid.parentElement;
                                    if (parent) {
                                        var playOverlay = parent.querySelector(
                                            'button[aria-label*="Play" i], div[aria-label*="Play" i], [data-testid*="playButton"], .vjs-big-play-button, [class*="play-button"], [class*="PlayButton"]'
                                        );
                                        if (playOverlay && playOverlay.offsetParent !== null) {
                                            playOverlay.click();
                                        }
                                    }
                                } catch(_) {}
                            }
                        } catch (_) {}
                    }

                    // IntersectionObserver for instantaneous detection as elements/scroll containers move
                    try {
                        if (window.IntersectionObserver) {
                            var vidObserver = new IntersectionObserver(function(entries) {
                                autoPlayVisibleVideo();
                            }, {
                                threshold: [0.3, 0.5, 0.75]
                            });

                            function observeAllVideos() {
                                var allVids = document.querySelectorAll('video');
                                for (var k = 0; k < allVids.length; k++) {
                                    if (!allVids[k].__hazeObservedUniversal) {
                                        allVids[k].__hazeObservedUniversal = true;
                                        vidObserver.observe(allVids[k]);
                                    }
                                }
                            }
                            observeAllVideos();

                            var mutObs = new MutationObserver(function() {
                                observeAllVideos();
                            });
                            mutObs.observe(document.body || document.documentElement, {
                                childList: true,
                                subtree: true
                            });
                        }
                    } catch(_) {}

                    window.addEventListener('scroll', autoPlayVisibleVideo, { capture: true, passive: true });
                    document.addEventListener('scroll', autoPlayVisibleVideo, { capture: true, passive: true });
                    document.addEventListener('touchmove', autoPlayVisibleVideo, { passive: true });
                    document.addEventListener('touchend', function() {
                        setTimeout(autoPlayVisibleVideo, 50);
                        setTimeout(autoPlayVisibleVideo, 200);
                        setTimeout(autoPlayVisibleVideo, 500);
                    }, { passive: true });
                    setInterval(autoPlayVisibleVideo, 800);
                    window.__hazeCheckUniversalAutoplay = autoPlayVisibleVideo;
                })();
            })();
        """.trimIndent()

        val SPOTIFY_DESKTOP_HELPER_SCRIPT = SPOTIFY_ENGINE_SCRIPT
        val SPOTIFY_CLICK_AND_AD_SCRIPT = SPOTIFY_ENGINE_SCRIPT
        val SPOTIFY_NEUTRALIZER_SCRIPT = SPOTIFY_ENGINE_SCRIPT

        val DESKTOP_VIEWPORT_SCRIPT = """
            (function() {
                try {
                    var meta = document.querySelector('meta[name="viewport"]');
                    if (!meta) {
                        meta = document.createElement('meta');
                        meta.name = 'viewport';
                        (document.head || document.documentElement).appendChild(meta);
                    }
                    meta.setAttribute('content', 'width=1280');
                } catch(e) {}
            })();
        """.trimIndent()

        /**
         * Overrides navigator.userAgentData so sites that check client hints (YouTube, Instagram,
         * Discord, etc.) see a Windows desktop browser instead of Android mobile.
         */
        val DESKTOP_CLIENT_HINTS_SCRIPT = """
            (function() {
                if (window.__hazeDesktopClientHintsApplied) return;
                window.__hazeDesktopClientHintsApplied = true;
                try {
                    // Override platform to Windows
                    Object.defineProperty(navigator, 'platform', {
                        get: function() { return 'Win32'; },
                        configurable: true
                    });
                    // Override maxTouchPoints so sites don't detect touch-only device
                    Object.defineProperty(navigator, 'maxTouchPoints', {
                        get: function() { return 0; },
                        configurable: true
                    });
                    // Override userAgentData if present (Client Hints API — Chrome 90+)
                    if (navigator.userAgentData) {
                        try {
                            Object.defineProperty(navigator.userAgentData, 'mobile', {
                                get: function() { return false; },
                                configurable: true
                            });
                            Object.defineProperty(navigator.userAgentData, 'platform', {
                                get: function() { return 'Windows'; },
                                configurable: true
                            });
                        } catch(_) {}
                    }
                } catch(_) {}
            })();
        """.trimIndent()

        val MOBILE_VIEWPORT_SCRIPT = """
            (function() {
                try {
                    var meta = document.querySelector('meta[name="viewport"]');
                    if (meta) {
                        meta.setAttribute('content', 'width=device-width, initial-scale=1.0');
                    }
                } catch(e) {}
            })();
        """.trimIndent()

        val DYNAMIC_BANNER_CLEANER_SCRIPT = """
            (function() {
                var host = window.location.hostname || '';
                var href = window.location.href || '';
                if (host.indexOf('youtube.com') !== -1 || href.indexOf('youtube.com') !== -1 || host.indexOf('spotify.com') !== -1 || href.indexOf('spotify.com') !== -1) return;
                if (window.__hazeDynamicBannerCleanerInjected) return;
                window.__hazeDynamicBannerCleanerInjected = true;
                try {
                    var s = document.getElementById('__haze_dynamic_banner_css');
                    if (!s) {
                        s = document.createElement('style');
                        s.id = '__haze_dynamic_banner_css';
                        s.type = 'text/css';
                        s.textContent = 'ins.adsbygoogle, iframe[id*="google_ads"], iframe[src*="doubleclick.net"], iframe[src*="googlesyndication.com"], iframe[src*="googleadservices.com"], iframe[src*="amazon-adsystem.com"], iframe[src*="adnxs.com"], iframe[src*="criteo.com"], iframe[src*="pubmatic.com"], iframe[src*="openx.net"], iframe[src*="rubiconproject.com"], iframe[src*="smartadserver.com"], iframe[src*="teads.tv"], iframe[src*="adform.net"], div[id*="google_ads"], div[id^="dfp-"], div[id^="gpt-"], div[id*="gpt-ad"], div[id^="div-gpt-ad"], div.ad-slot, div.ad-container, div.ad-wrapper, div.ad-section, div.ad-banner, div.ad-box, div.ad-unit, div.ad-holder, div.ad-space, div.ad-area, div.ad-placeholder, div.advertisement, div.advertisement-container, div.advertisement-wrapper, div.advertisement-banner, div.advertisement-slot, div.advertisement-box, div.advertisement-section, div.advertisement-placeholder, section.advertisement, section.ad-container, section.ad-slot, aside.advertisement, aside.ad-container, aside.ad-slot, .ad-banner, .banner-ad, .banner_ad, .ad-slot, .ad-box, .ad-container, .ad-wrapper, .ad-unit, .ad-holder, .ad-space, .ad-area, .ad-section, .ad-placeholder, .ad-label, .ad-notice, .ad-title, .ad-header, .ad-caption, .advertisement-label, .ad-badge, .sponsored-label, .sponsored-tag, .advertisement, [data-ad-unit], [data-ad-slot], [data-ad-client], [data-ad-name], [data-ad-format], [data-ad-container], [data-ad-wrapper], [data-ad-placeholder], [data-google-query-id], [aria-label="Advertisement" i], [aria-label="Sponsored" i], [aria-label="Advert" i], [aria-label="Anuncio" i], [data-advertisement], .sponsored-post, .sponsored-content, .promoted-content, .sponsored-badge, .sponsored-wrapper, .sponsored-container, .header-ad, .footer-ad, .sidebar-ad, .top-ad, .bottom-ad, .inline-ad, .in-article-ad, .in-content-ad, .native-ad, .native_ad, .sticky-ad, .floating-ad, .tbl-feed-container, .trc_rbox_div, div[id*="taboola"], .outbrain_widget, .OB-widget, div[id*="rc-widget"], .mgbox, .mgid-widget, .ima-ad-container, .video-ad-overlay, .vjs-ad-loading, .jw-flag-ads { display: none !important; height: 0 !important; max-height: 0 !important; min-height: 0 !important; width: 0 !important; max-width: 0 !important; min-width: 0 !important; margin: 0 !important; padding: 0 !important; border: none !important; outline: none !important; opacity: 0 !important; pointer-events: none !important; overflow: hidden !important; clip: rect(0, 0, 0, 0) !important; }';
                        (document.head || document.documentElement).appendChild(s);
                    }
                } catch(_) {}

                // 2. Unsolicited Popup & Popunder Interceptor
                try {
                    var origWindowOpen = window.open;
                    window.open = function(url, target, features) {
                        var strUrl = (url || '').toString().toLowerCase();
                        if (strUrl && (
                            strUrl.indexOf('popads') !== -1 || strUrl.indexOf('popcash') !== -1 ||
                            strUrl.indexOf('propeller') !== -1 || strUrl.indexOf('adsterra') !== -1 ||
                            strUrl.indexOf('exoclick') !== -1 || strUrl.indexOf('hilltopads') !== -1 ||
                            strUrl.indexOf('clickadu') !== -1 || strUrl.indexOf('adcash') !== -1 ||
                            strUrl.indexOf('popunder') !== -1 || strUrl.indexOf('clickunder') !== -1 ||
                            strUrl.indexOf('zeroredirect') !== -1 || strUrl.indexOf('tsyndicate') !== -1 ||
                            strUrl.indexOf('ad-maven') !== -1 || strUrl.indexOf('clicksor') !== -1 ||
                            strUrl.indexOf('trafficjunky') !== -1 || strUrl.indexOf('juicyads') !== -1 ||
                            strUrl.indexOf('exosrv') !== -1
                        )) {
                            return null;
                        }
                        return origWindowOpen.apply(this, arguments);
                    };
                } catch(_) {}

                // 3. Notification Spam Protection (Deceptive 'Click allow to continue' prompts)
                try {
                    if (window.Notification && window.Notification.requestPermission) {
                        window.Notification.requestPermission = function(cb) {
                            var p = Promise.resolve('denied');
                            if (typeof cb === 'function') cb('denied');
                            return p;
                        };
                    }
                } catch(_) {}

                // 4. Annoying Overlays & Cookie Consent Wall Dismissal
                function dismissAnnoyingOverlays() {
                    try {
                        var overlaySelectors = [
                            '.sp_message_container_root', '[id^="sp_message_container"]', '.fc-consent-root',
                            'div[class*="cookie-banner"]', 'div[id*="cookie-banner"]',
                            'div[class*="cookie-consent"]', 'div[id*="cookie-consent"]',
                            'div[class*="cookie-notice"]', 'div[id*="cookie-notice"]',
                            '#onetrust-consent-sdk', 'div[class*="newsletter-popup"]',
                            'div[class*="subscribe-modal"]', 'div[class*="paywall-overlay"]',
                            'div[id*="paywall-overlay"]', '.tp-modal', '.tp-backdrop',
                            '.interstitial-ad', 'div[class*="interstitial"]', 'div[id*="interstitial"]'
                        ];
                        var found = document.querySelectorAll(overlaySelectors.join(','));
                        for (var k = 0; k < found.length; k++) {
                            found[k].style.setProperty('display', 'none', 'important');
                            found[k].style.setProperty('height', '0px', 'important');
                            found[k].style.setProperty('min-height', '0px', 'important');
                            found[k].style.setProperty('pointer-events', 'none', 'important');
                        }

                        // Unlock page scroll if locked by an overlay
                        var docEl = document.documentElement;
                        var body = document.body;
                        if (docEl && getComputedStyle(docEl).overflow === 'hidden') {
                            docEl.style.setProperty('overflow', 'auto', 'important');
                        }
                        if (body && getComputedStyle(body).overflow === 'hidden') {
                            body.style.setProperty('overflow', 'auto', 'important');
                        }
                    } catch(_) {}
                }

                function isStrictAdIdentifier(s) {
                    if (!s || typeof s !== 'string') return false;
                    // Strip non-ad words that contain 'ad'
                    var clean = s.replace(/\b(read|lead|thread|road|broad|shadow|head|load|download|upload)[-_a-z0-9]*/gi, '');
                    return /(?:^|[\s_-])(?:ads?|advertisement|advert|sponsored|dfp|gpt-ad|google_ads|banner-ad|ad-slot|ad-unit|ad-container|ad-wrapper|taboola|outbrain|revcontent|mgid)(?:[\s_-]|$)/i.test(clean);
                }

                // 5. Intelligent Ad Section Collapser & Remover
                function collapseElementAndSection(el) {
                    if (!el || !el.parentElement) return;
                    var tag = (el.tagName || '').toLowerCase();
                    if (tag === 'body' || tag === 'html' || tag === 'main' || tag === 'article' || tag === 'header' || tag === 'nav' || tag === 'footer' || tag === 'form' || tag.indexOf('yt') === 0 || tag.indexOf('spotify') === 0) return;

                    try {
                        // Collapse element itself
                        el.style.setProperty('display', 'none', 'important');
                        el.style.setProperty('height', '0px', 'important');
                        el.style.setProperty('min-height', '0px', 'important');
                        el.style.setProperty('max-height', '0px', 'important');
                        el.style.setProperty('margin', '0px', 'important');
                        el.style.setProperty('padding', '0px', 'important');
                        el.style.setProperty('border', 'none', 'important');
                        el.style.setProperty('visibility', 'hidden', 'important');
                        el.style.setProperty('pointer-events', 'none', 'important');

                        // Climb parent hierarchy to collapse ONLY genuine empty ad wrappers / ad slots
                        var curr = el.parentElement;
                        var levels = 0;
                        while (curr && levels < 3) {
                            var pTag = (curr.tagName || '').toLowerCase();
                            if (pTag === 'body' || pTag === 'html' || pTag === 'main' || pTag === 'article' || pTag === 'header' || pTag === 'nav' || pTag === 'footer' || pTag === 'form') {
                                break;
                            }

                            // If this parent has any headings or an article role or substantive text, DO NOT collapse it!
                            if (curr.querySelector('article, h1, h2, h3, h4, [role="article"]')) {
                                break;
                            }

                            var pCls = (curr.className || '').toString();
                            var pId = (curr.id || '').toString();
                            var isExplicitAdContainer = isStrictAdIdentifier(pCls) || isStrictAdIdentifier(pId);

                            var hasRealContent = false;
                            for (var i = 0; i < curr.children.length; i++) {
                                var child = curr.children[i];
                                if (child === el) continue;
                                var cTag = (child.tagName || '').toLowerCase();
                                if (cTag === 'script' || cTag === 'style' || cTag === 'noscript' || cTag === 'template') continue;
                                if (child.style && child.style.display === 'none') continue;
                                
                                var cText = (child.textContent || '').trim();
                                if (/^(advertisement|sponsored|promoted|advert|anuncio|publicidad|werbung|anzeige|ads? by google|partner content)$/i.test(cText)) {
                                    child.style.setProperty('display', 'none', 'important');
                                    continue;
                                }
                                var cCls = (child.className || '').toString();
                                var cId = (child.id || '').toString();
                                if (isStrictAdIdentifier(cCls) || isStrictAdIdentifier(cId) || cTag === 'ins' || cTag === 'iframe') {
                                    continue;
                                }
                                if (cText.length > 40 || child.querySelector('img, video, p, article, h1, h2, h3, h4, a[href]')) {
                                    hasRealContent = true;
                                    break;
                                }
                            }

                            if (isExplicitAdContainer && !hasRealContent) {
                                curr.style.setProperty('display', 'none', 'important');
                                curr.style.setProperty('height', '0px', 'important');
                                curr.style.setProperty('min-height', '0px', 'important');
                                curr.style.setProperty('max-height', '0px', 'important');
                                curr.style.setProperty('margin', '0px', 'important');
                                curr.style.setProperty('padding', '0px', 'important');
                                curr.style.setProperty('border', 'none', 'important');
                                curr.style.setProperty('visibility', 'hidden', 'important');
                                curr.style.setProperty('pointer-events', 'none', 'important');
                            } else {
                                break;
                            }
                            curr = curr.parentElement;
                            levels++;
                        }
                    } catch(_) {}
                }

                function cleanAdSections() {
                    try {
                        dismissAnnoyingOverlays();

                        // A. Clean ad iframes
                        var adIframes = document.querySelectorAll('iframe[src*="doubleclick.net"], iframe[src*="googlesyndication.com"], iframe[src*="googleadservices.com"], iframe[src*="amazon-adsystem.com"], iframe[src*="adnxs.com"], iframe[src*="criteo.com"], iframe[src*="pubmatic.com"], iframe[src*="openx.net"], iframe[src*="rubiconproject.com"], iframe[src*="smartadserver.com"], iframe[src*="teads.tv"], iframe[src*="adform.net"], iframe[id*="google_ads"]');
                        for (var j = 0; j < adIframes.length; j++) {
                            var frame = adIframes[j];
                            try { frame.src = 'about:blank'; } catch(_) {}
                            collapseElementAndSection(frame);
                        }

                        // B. Clean ad containers, slots, ins, and sections
                        var adElements = document.querySelectorAll('ins.adsbygoogle, [id*="google_ads"], [id^="dfp-"], [id^="gpt-"], [id*="gpt-ad"], [id^="div-gpt-ad"], [data-ad-unit], [data-ad-slot], [data-ad-client], [data-ad-container], [data-ad-placeholder], [data-google-query-id], [aria-label="Advertisement" i], [aria-label="Sponsored" i], div.ad-slot, div.ad-container, div.ad-wrapper, div.ad-section, div.ad-banner, div.advertisement, section.advertisement, section.ad-container, aside.advertisement');
                        for (var k = 0; k < adElements.length; k++) {
                            collapseElementAndSection(adElements[k]);
                        }

                        // C. Ad Label Section Hunter (find elements whose label is ADVERTISEMENT/SPONSORED)
                        var labelCandidates = document.querySelectorAll('.ad-badge, .sponsored-badge, .ad-label, .sponsored-label, .ad-text, [data-ad-label]');
                        for (var m = 0; m < labelCandidates.length; m++) {
                            var cand = labelCandidates[m];
                            if (cand.children.length === 0) {
                                var txt = (cand.textContent || '').trim().toLowerCase();
                                if (/^(advertisement|sponsored|promoted|advert|anuncio|publicidad|werbung|anzeige|ads? by google|partner content)$/i.test(txt)) {
                                    collapseElementAndSection(cand);
                                }
                            }
                        }
                    } catch(_) {}
                }

                window.__hazeCleanAdSections = cleanAdSections;

                cleanAdSections();
                if (document.readyState === 'loading') {
                    document.addEventListener('DOMContentLoaded', cleanAdSections);
                }
                window.addEventListener('load', cleanAdSections);
            })();
        """.trimIndent()
    }
}
