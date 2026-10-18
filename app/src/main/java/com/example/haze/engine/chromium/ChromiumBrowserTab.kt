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
            if (visibility == View.VISIBLE) onResume()
            else onPause()
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

        val isDark = isPrivate || webView.context.getSharedPreferences("HazePrefs", Context.MODE_PRIVATE).getBoolean("is_dark", false)
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
                    if (HazePrivacyShieldEngine.isShieldEnabled(webView.context)) {
                        val domain = runCatching { Uri.parse(nonNullUrl).host }.getOrNull()
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
                if (nonNullUrl.contains("google.com", ignoreCase = true)) {
                    val extractGoogleAvatarScript = """
                        (function() {
                            function extractGoogleProfile() {
                                try {
                                    var img = document.querySelector('a[aria-label*="Google Account" i] img, a[aria-label*="Compte Google" i] img, a[aria-label*="Cuenta de Google" i] img, img[alt*="Google Account" i], img[alt*="Profile photo" i], img[src*="googleusercontent.com/a/"], img[src*="googleusercontent.com/ogw/"]');
                                    var avatarUrl = img ? (img.src || '') : '';
                                    
                                    // Also check aria-label or title for display name and email
                                    var accountBtn = document.querySelector('a[aria-label*="Google Account" i], a[aria-label*="@" i]');
                                    var label = accountBtn ? (accountBtn.getAttribute('aria-label') || '') : '';
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
                                        var highRes = avatarUrl.replace(/=s\d+(-c)?/i, '=s192-c');
                                        window.Android.syncGoogleAccountDetails(email, name, highRes);
                                    }
                                } catch(_) {}
                            }
                            setTimeout(extractGoogleProfile, 1000);
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

                // 1. Comprehensive AdGuard/uBlock Cosmetic CSS Injection
                var ytCosmeticSelectors = isYtMusic ? [
                    'ytmusic-ad-slot-renderer',
                    'ytmusic-in-feed-ad-layout-renderer',
                    'ytmusic-display-ad-renderer',
                    'ytmusic-banner-promo-renderer',
                    'ytmusic-upsell-dialog-renderer',
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
                    'ytd-engagement-panel-section-list-renderer[target-id="engagement-panel-ads"]',
                    'ytd-player-legacy-desktop-watch-ads-renderer',
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
                    'ytd-enforcement-message-view-model',
                    '#enforcement-message',
                    'tp-yt-paper-dialog:has(ytd-enforcement-message-view-model)',
                    'tp-yt-paper-dialog:has(#feedback)',
                    'yt-playability-error-supported-renderers',
                    'ytm-search ytm-compact-promoted-video-renderer',
                    '.ad-container',
                    '.ad-div'
                ];
                var ytCosmeticCss = ytCosmeticSelectors.join(', ') + ' { display: none !important; opacity: 0 !important; visibility: hidden !important; pointer-events: none !important; height: 0 !important; max-height: 0 !important; min-height: 0 !important; margin: 0 !important; padding: 0 !important; border: 0 !important; }' +
                    '\nvideo, video.video-stream, video.html5-main-video, .html5-main-video { opacity: 1 !important; visibility: visible !important; pointer-events: auto !important; transform: translateZ(0) !important; backface-visibility: hidden !important; will-change: transform !important; }' +
                    (isYtMusic ? '\nhtml, body { overflow: hidden !important; height: 100% !important; min-height: 100% !important; width: 100% !important; position: fixed !important; } ytmusic-app { height: 100% !important; min-height: 100% !important; display: block !important; }' : '');

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

                // 2. Deep Cleaning of Player Payloads
                function cleanPlayerPayload(obj, isReelEndpoint) {
                    if (!obj || typeof obj !== 'object') return;
                    try {
                        delete obj.adPlacements;
                        delete obj.adSlots;
                        delete obj.playerAds;
                        delete obj.adBreakHeartbeatParams;
                        delete obj.interstitials;
                        delete obj.reelPlayerParams;
                        delete obj.midrollInfo;

                        // Only filter entries/items when specifically on reel (Shorts) endpoints
                        if (isReelEndpoint) {
                            if (Array.isArray(obj.entries)) {
                                obj.entries = obj.entries.filter(function(entry) {
                                    if (!entry) return false;
                                    if (entry.adSlot || entry.adSlotRenderer || entry.promotedSparklesWebRenderer || entry.promotedVideoRenderer) {
                                        return false;
                                    }
                                    return true;
                                });
                            }
                            if (Array.isArray(obj.items)) {
                                obj.items = obj.items.filter(function(item) {
                                    if (!item) return false;
                                    return !item.adSlot && !item.adSlotRenderer;
                                });
                            }
                        }

                        if (obj.playerResponse && typeof obj.playerResponse === 'object') {
                            cleanPlayerPayload(obj.playerResponse, isReelEndpoint);
                        }
                    } catch(_) {}
                }

                // 3. Intercept ytInitialPlayerResponse
                var _ytInitialPlayerResp = window.ytInitialPlayerResponse;
                if (_ytInitialPlayerResp && typeof _ytInitialPlayerResp === 'object') {
                    cleanPlayerPayload(_ytInitialPlayerResp, false);
                }
                try {
                    Object.defineProperty(window, 'ytInitialPlayerResponse', {
                        get: function() { return _ytInitialPlayerResp; },
                        set: function(val) {
                            cleanPlayerPayload(val, false);
                            _ytInitialPlayerResp = val;
                        },
                        configurable: true
                    });
                } catch(_) {}

                // 4. Intercept Fetch & Response.prototype
                try {
                    var origFetch = window.fetch;
                    window.fetch = function(input, init) {
                        var url = (typeof input === 'string') ? input : (input && input.url) ? input.url : '';
                        if (url && (
                            url.indexOf('/api/stats/ads') !== -1 ||
                            url.indexOf('/pagead/') !== -1 ||
                            url.indexOf('/youtubei/v1/player/ad_break') !== -1 ||
                            url.indexOf('/ptracking') !== -1 ||
                            url.indexOf('/get_midroll_info') !== -1
                        )) {
                            return Promise.resolve(new Response('', { status: 204, statusText: 'No Content' }));
                        }
                        return origFetch.apply(this, arguments);
                    };

                    var origJson = Response.prototype.json;
                    Response.prototype.json = function() {
                        var selfUrl = this.url || '';
                        var isReel = selfUrl.indexOf('/youtubei/v1/reel/') !== -1;
                        var isYtPlayerReq = selfUrl.indexOf('/youtubei/v1/player') !== -1 ||
                                            isReel ||
                                            selfUrl.indexOf('v1/player') !== -1;
                        return origJson.apply(this, arguments).then(function(data) {
                            if (isYtPlayerReq && data && typeof data === 'object') {
                                cleanPlayerPayload(data, isReel);
                            }
                            return data;
                        });
                    };

                    var origText = Response.prototype.text;
                    Response.prototype.text = function() {
                        var selfUrl = this.url || '';
                        var isReel = selfUrl.indexOf('/youtubei/v1/reel/') !== -1;
                        var isYtPlayerReq = selfUrl.indexOf('/youtubei/v1/player') !== -1 ||
                                            isReel ||
                                            selfUrl.indexOf('v1/player') !== -1;
                        return origText.apply(this, arguments).then(function(text) {
                            if (isYtPlayerReq && text && (text.indexOf('adPlacements') !== -1 || text.indexOf('playerAds') !== -1 || text.indexOf('adSlots') !== -1)) {
                                try {
                                    var parsed = JSON.parse(text);
                                    cleanPlayerPayload(parsed, isReel);
                                    return JSON.stringify(parsed);
                                } catch(_) {}
                            }
                            return text;
                        });
                    };
                } catch(_) {}

                // 5. Intercept XMLHttpRequest safely (Guarded responseType)
                try {
                    var origXhrOpen = XMLHttpRequest.prototype.open;
                    var origXhrSend = XMLHttpRequest.prototype.send;
                    XMLHttpRequest.prototype.open = function(method, url) {
                        this.__hazeYtUrl = url || '';
                        this.__hazeIsPlayer = (typeof url === 'string') && (
                            url.indexOf('/youtubei/v1/player') !== -1 ||
                            url.indexOf('/youtubei/v1/reel/') !== -1 ||
                            url.indexOf('v1/player') !== -1
                        );
                        this.__hazeIsReel = (typeof url === 'string') && (url.indexOf('/youtubei/v1/reel/') !== -1);
                        return origXhrOpen.apply(this, arguments);
                    };
                    XMLHttpRequest.prototype.send = function() {
                        var url = this.__hazeYtUrl;
                        if (url && (
                            url.indexOf('/api/stats/ads') !== -1 ||
                            url.indexOf('/pagead/') !== -1 ||
                            url.indexOf('/youtubei/v1/player/ad_break') !== -1 ||
                            url.indexOf('/ptracking') !== -1 ||
                            url.indexOf('/get_midroll_info') !== -1
                        )) {
                            var self = this;
                            setTimeout(function() {
                                try {
                                    Object.defineProperty(self, 'status', { get: function() { return 204; } });
                                    Object.defineProperty(self, 'responseText', { get: function() { return ''; } });
                                    if (typeof self.onreadystatechange === 'function') self.onreadystatechange();
                                    if (typeof self.onload === 'function') self.onload();
                                } catch(_) {}
                            }, 0);
                            return;
                        }
                        return origXhrSend.apply(this, arguments);
                    };

                    var origResponseTextGetter = Object.getOwnPropertyDescriptor(XMLHttpRequest.prototype, 'responseText');
                    if (origResponseTextGetter && origResponseTextGetter.get) {
                        var origGet = origResponseTextGetter.get;
                        Object.defineProperty(XMLHttpRequest.prototype, 'responseText', {
                            get: function() {
                                try {
                                    if (this.responseType && this.responseType !== '' && this.responseType !== 'text') {
                                        return origGet.apply(this);
                                    }
                                } catch(_) {
                                    return origGet.apply(this);
                                }
                                var val = origGet.apply(this);
                                if (this.__hazeIsPlayer && typeof val === 'string') {
                                    if (this.__hazeCleanedText) return this.__hazeCleanedText;
                                    try {
                                        var data = JSON.parse(val);
                                        cleanPlayerPayload(data, this.__hazeIsReel);
                                        this.__hazeCleanedText = JSON.stringify(data);
                                        return this.__hazeCleanedText;
                                    } catch(_) {}
                                }
                                return val;
                            },
                            configurable: true
                        });
                    }
                } catch(_) {}

                // 6. ytcfg experiment flags
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

                // 7. Instant 0ms Ad Killer (Synchronous, Event-Driven, No Buffering Stalls)
                function killAdInstantly() {
                    try {
                        var player = document.getElementById('movie_player') || document.querySelector('.html5-video-player');
                        var video = document.querySelector('video.html5-main-video') || document.querySelector('video');
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

                            // 3. Fast-forward ad video smoothly without locking up playback engine
                            if (video) {
                                video.__hazeWasAd = true;
                                video.muted = true;
                                if (isFinite(video.duration) && video.duration > 0 && video.currentTime < video.duration) {
                                    video.playbackRate = 16.0;
                                    video.currentTime = video.duration - 0.05;
                                }
                            }

                            // 4. Remove enforcement dialogs and error modals
                            var enforcement = document.querySelectorAll(
                                'ytd-enforcement-message-view-model, #enforcement-message, yt-playability-error-supported-renderers, ' +
                                'ytm-mealbar-promo-renderer, ytm-upsell-dialog-renderer, ytd-mealbar-promo-renderer'
                            );
                            for (var i = 0; i < enforcement.length; i++) enforcement[i].remove();
                            var backdrops = document.querySelectorAll('tp-yt-iron-overlay-backdrop');
                            for (var b = 0; b < backdrops.length; b++) backdrops[b].remove();
                        } else if (video && video.__hazeWasAd) {
                            video.__hazeWasAd = false;
                            if (video.playbackRate > 2.0) {
                                video.playbackRate = 1.0;
                            }
                            if (video.muted && !video.paused) {
                                video.muted = false;
                            }
                        }
                    } catch(_) {}
                }

                // Hook video element events synchronously (NO timeupdate polling)
                function monitorVideo(v) {
                    if (!v || v.__hazeMonitored) return;
                    v.__hazeMonitored = true;
                    v.addEventListener('loadedmetadata', killAdInstantly, true);
                    v.addEventListener('play', killAdInstantly, true);
                    v.addEventListener('playing', killAdInstantly, true);
                    v.addEventListener('loadstart', killAdInstantly, true);
                }

                var existingVideo = document.querySelector('video');
                if (existingVideo) monitorVideo(existingVideo);

                // Watch for new video elements and player class mutations (throttled)
                var killAdScheduled = false;
                function scheduleKillAd() {
                    if (killAdScheduled) return;
                    killAdScheduled = true;
                    requestAnimationFrame(function() {
                        killAdScheduled = false;
                        killAdInstantly();
                    });
                }

                var observer = new MutationObserver(function(mutations) {
                    for (var i = 0; i < mutations.length; i++) {
                        var m = mutations[i];
                        for (var j = 0; j < m.addedNodes.length; j++) {
                            var node = m.addedNodes[j];
                            if (node.nodeType === 1) {
                                if (node.tagName === 'VIDEO') {
                                    monitorVideo(node);
                                    scheduleKillAd();
                                } else if (node.querySelector) {
                                    var nested = node.querySelector('video');
                                    if (nested) {
                                        monitorVideo(nested);
                                        scheduleKillAd();
                                    }
                                }
                            }
                        }
                        if (m.type === 'attributes' && m.attributeName === 'class') {
                            var targetCls = (m.target && m.target.className) || '';
                            if (typeof targetCls === 'string' && (targetCls.indexOf('ad-showing') !== -1 || targetCls.indexOf('ad-interrupting') !== -1)) {
                                scheduleKillAd();
                            }
                        }
                    }
                });

                var rootToObserve = document.getElementById('movie_player') || document.querySelector('.html5-video-player') || document.body;
                if (rootToObserve) {
                    observer.observe(rootToObserve, {
                        childList: true,
                        subtree: true,
                        attributes: true,
                        attributeFilter: ['class']
                    });
                } else {
                    document.addEventListener('DOMContentLoaded', function() {
                        var target = document.getElementById('movie_player') || document.querySelector('.html5-video-player') || document.body;
                        if (target) {
                            observer.observe(target, {
                                childList: true,
                                subtree: true,
                                attributes: true,
                                attributeFilter: ['class']
                            });
                        }
                    });
                }

                document.addEventListener('yt-navigate-finish', killAdInstantly);
                document.addEventListener('yt-page-data-updated', killAdInstantly);
                killAdInstantly();

                // 8. YouTube Music Auto-Play & Smooth Playback Guard
                if (isYtMusic) {
                    function ensureMusicSmoothPlay() {
                        try {
                            var mp = document.getElementById('movie_player');
                            var v = document.querySelector('video');
                            if (mp && typeof mp.getPlayerState === 'function') {
                                var s = mp.getPlayerState();
                                if ((s === -1 || s === 5 || (s === 3 && v && v.paused)) && typeof mp.playVideo === 'function') {
                                    mp.playVideo();
                                }
                            } else if (v && v.paused && v.readyState >= 1) {
                                v.play().catch(function(){});
                            }
                        } catch(_) {}
                    }
                    document.addEventListener('yt-navigate-finish', function() {
                        setTimeout(ensureMusicSmoothPlay, 200);
                        setTimeout(ensureMusicSmoothPlay, 800);
                    });
                    document.addEventListener('click', function(e) {
                        var playBtn = e.target && e.target.closest && e.target.closest('ytmusic-play-button-renderer, [aria-label*="Play" i], .play-button');
                        if (playBtn) {
                            setTimeout(ensureMusicSmoothPlay, 250);
                        }
                    }, true);
                }
            })();
        """.trimIndent()

        val ADGUARD_UNIVERSAL_SHIELD_SCRIPT = """
            (function() {
                var host = window.location.hostname || '';
                var isYtMusic = host.indexOf('music.youtube.com') !== -1;
                var isSpotify = host.indexOf('spotify.com') !== -1;
                if (window.__hazeAdGuardShieldInjected) return;
                window.__hazeAdGuardShieldInjected = true;

                // On music.youtube.com and spotify.com, do NOT inject generic class selectors that conflict with core player frameworks
                if (isYtMusic || isSpotify) return;

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
                    } catch(_) {}
                }
                if (document.body) cleanOverlays();
                else window.addEventListener('DOMContentLoaded', cleanOverlays);
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
                // 1. Declarative CSS injection for all known ad containers, banners, slots, sections, and wrappers
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
                    if (tag === 'body' || tag === 'html' || tag === 'main' || tag === 'article' || tag === 'header' || tag === 'nav' || tag === 'footer' || tag === 'form') return;

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
