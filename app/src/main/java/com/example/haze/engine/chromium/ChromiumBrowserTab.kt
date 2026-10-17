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
            @Suppress("DEPRECATION")
            setRenderPriority(WebSettings.RenderPriority.HIGH)

            if (isPrivate) {
                cacheMode = WebSettings.LOAD_NO_CACHE
            } else {
                cacheMode = WebSettings.LOAD_DEFAULT
            }

            // Pre-rasterizing hidden tabs increases memory pressure and can
            // compete with the visible page for raster threads.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                offscreenPreRaster = true
            }
        }

        if (config.enableHardwareAcceleration) {
            webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        }
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

                    // Enforce correct UA on every page navigation (prevents UA reset after SPA routing)
                    if (isSpotify) {
                        try {
                            android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
                        } catch (_: Throwable) {}
                        view?.settings?.userAgentString = if (isDesktop) SPOTIFY_DESKTOP_UA else MOBILE_USER_AGENT
                        view?.settings?.useWideViewPort = isDesktop
                        view?.settings?.loadWithOverviewMode = isDesktop
                    } else {
                        val ua = if (isDesktop) DESKTOP_USER_AGENT else MOBILE_USER_AGENT
                        view?.settings?.userAgentString = ua
                        view?.settings?.useWideViewPort = isDesktop
                        view?.settings?.loadWithOverviewMode = isDesktop
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
                observer?.onReceivedError(
                    this@ChromiumBrowserTab,
                    errorCode,
                    description ?: "Unknown error",
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
                    val failingUrl = request.url?.toString() ?: url
                    observer?.onReceivedError(this@ChromiumBrowserTab, errorCode, desc, failingUrl)
                }
            }

            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                val cert = error?.certificate
                val sslInfo = SslErrorInfo(
                    primaryError = error?.primaryError ?: -1,
                    url = error?.url ?: url,
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
                HazeDevToolsManager.recordNetworkRequest(
                    tabId = id,
                    url = reqUrl,
                    method = request.method ?: "GET",
                    headers = request.requestHeaders ?: emptyMap(),
                    isMainFrame = isMain,
                    isBlocked = decision.shouldBlock
                )
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

                // 1.5 - 2.0 GB Fluid Cache Engine (0-1ms cache serving)
                val cachedResponse = com.example.haze.HazeFluidCacheEngine.interceptRequest(
                    context = ctx,
                    request = request,
                    isPrivate = isPrivate
                )
                if (cachedResponse != null) {
                    return cachedResponse
                }

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
                    HazeDevToolsManager.recordConsoleMessage(
                        tabId = id,
                        message = consoleMessage.message(),
                        sourceId = consoleMessage.sourceId(),
                        lineNumber = consoleMessage.lineNumber(),
                        level = level
                    )
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
        const val SPOTIFY_DESKTOP_UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        // A clean mobile UA that looks like Android Chrome — avoids WebView fingerprinting
        const val MOBILE_USER_AGENT = "Mozilla/5.0 (Linux; Android 11; Redmi Note 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

        val SPOTIFY_ENGINE_SCRIPT = """
            (function() {
                if (window.__hazeSpotifyEngineInitialized) return;
                window.__hazeSpotifyEngineInitialized = true;

                // 1. Spoofer for Desktop Chrome on Linux (Optimal Spotify Web Player support)
                try {
                    Object.defineProperty(navigator, 'platform', {
                        get: function() { return 'Linux x86_64'; },
                        configurable: true
                    });
                    if (navigator.userAgentData) {
                        try {
                            Object.defineProperty(navigator.userAgentData, 'mobile', { get: function() { return false; }, configurable: true });
                            Object.defineProperty(navigator.userAgentData, 'platform', { get: function() { return 'Linux'; }, configurable: true });
                        } catch(_) {}
                    }
                } catch(_) {}

                // 2. Network Interceptor: Hook fetch & XHR to return mock 200 OK for ad logic
                try {
                    var origFetch = window.fetch;
                    window.fetch = function(input, init) {
                        var url = (typeof input === 'string') ? input : (input && input.url ? input.url : '');
                        if (url && (
                            url.indexOf('/ad-logic/') !== -1 ||
                            url.indexOf('/melody/v1/ad') !== -1 ||
                            url.indexOf('/commercial-break/') !== -1 ||
                            url.indexOf('/audio-ad/') !== -1 ||
                            url.indexOf('/ad-slot/') !== -1 ||
                            url.indexOf('audio-ak-spotify-com') !== -1 ||
                            url.indexOf('doubleclick.net') !== -1 ||
                            url.indexOf('googleads') !== -1
                        )) {
                            return Promise.resolve(new Response(JSON.stringify({
                                ad: null,
                                ads: [],
                                breaks: [],
                                slot: "audio_slot",
                                duration: 0,
                                eligible: false
                            }), {
                                status: 200,
                                statusText: 'OK',
                                headers: { 'Content-Type': 'application/json' }
                            }));
                        }
                        return origFetch.apply(this, arguments);
                    };
                } catch(_) {}

                try {
                    var origOpen = XMLHttpRequest.prototype.open;
                    var origSend = XMLHttpRequest.prototype.send;
                    XMLHttpRequest.prototype.open = function(method, url) {
                        this.__hazeUrl = url || '';
                        return origOpen.apply(this, arguments);
                    };
                    XMLHttpRequest.prototype.send = function() {
                        var url = this.__hazeUrl;
                        if (url && (
                            url.indexOf('/ad-logic/') !== -1 ||
                            url.indexOf('/melody/v1/ad') !== -1 ||
                            url.indexOf('/commercial-break/') !== -1 ||
                            url.indexOf('/audio-ad/') !== -1 ||
                            url.indexOf('/ad-slot/') !== -1 ||
                            url.indexOf('audio-ak-spotify-com') !== -1 ||
                            url.indexOf('doubleclick.net') !== -1 ||
                            url.indexOf('googleads') !== -1
                        )) {
                            Object.defineProperty(this, 'status', { get: function() { return 200; } });
                            Object.defineProperty(this, 'readyState', { get: function() { return 4; } });
                            Object.defineProperty(this, 'responseText', { get: function() { return '{"ad":null,"ads":[],"breaks":[],"slot":"audio_slot","duration":0,"eligible":false}'; } });
                            var self = this;
                            setTimeout(function() {
                                if (self.onload) self.onload();
                                if (self.onreadystatechange) self.onreadystatechange();
                            }, 5);
                            return;
                        }
                        return origSend.apply(this, arguments);
                    };
                } catch(_) {}

                function dismissSpotifyModals() {
                    try {
                        var modals = document.querySelectorAll('[data-testid="login-modal"], [data-testid="upsell-modal"], div[role="dialog"], div.GenericModal__overlay');
                        for (var i = 0; i < modals.length; i++) {
                            var closeBtn = modals[i].querySelector('button[aria-label*="Close" i], [data-testid="close-button"], button[data-testid="dismiss-button"]');
                            if (closeBtn) closeBtn.click();
                            else modals[i].remove();
                        }
                        var cookieBtn = document.getElementById('onetrust-accept-btn-handler');
                        if (cookieBtn) cookieBtn.click();
                    } catch(_) {}
                }

                // Clean up any residual alternate floating player
                try {
                    var oldPlayer = document.getElementById('__haze_floating_player');
                    if (oldPlayer) oldPlayer.remove();
                } catch(_) {}

                // Continuous Supervisor: Dismiss modals cleanly
                function superviseSpotify() {
                    try {
                        dismissSpotifyModals();
                    } catch(_) {}
                }
                setInterval(superviseSpotify, 350);

                // 6. CSS Styles: Ad removal, touch play buttons, responsive player layout
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
                            '[data-testid="upsell-modal"], [data-testid="login-modal"] { display: none !important; pointer-events: none !important; }',
                            'div.GenericModal__overlay { pointer-events: none !important; background: transparent !important; }'
                        ].join('\n');
                        (document.head || document.documentElement).appendChild(s);
                    }
                } catch(_) {}
            })();
        """.trimIndent()

        val YOUTUBE_ENGINE_SCRIPT = """
            (function() {
                if (window.__hazeYouTubeEngineInjected) return;
                window.__hazeYouTubeEngineInjected = true;

                // 1. Comprehensive AdGuard/uBlock Cosmetic CSS Injection
                var ytCosmeticSelectors = [
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
                    '.ad-div',
                    '.video-ads'
                ];
                var ytCosmeticCss = ytCosmeticSelectors.join(', ') + ' { display: none !important; opacity: 0 !important; visibility: hidden !important; pointer-events: none !important; height: 0 !important; max-height: 0 !important; min-height: 0 !important; margin: 0 !important; padding: 0 !important; border: 0 !important; }';

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

                // 2. Prototype Trapping on Object.prototype (json-prune / set-constant)
                try {
                    Object.defineProperty(Object.prototype, 'adPlacements', {
                        get: function() { return undefined; },
                        set: function() {},
                        configurable: true
                    });
                    Object.defineProperty(Object.prototype, 'playerAds', {
                        get: function() { return undefined; },
                        set: function() {},
                        configurable: true
                    });
                    Object.defineProperty(Object.prototype, 'adSlots', {
                        get: function() { return undefined; },
                        set: function() {},
                        configurable: true
                    });
                } catch(_) {}

                // 3. Deep Cleaning of Player Payloads (Desktop & Mobile)
                function cleanPlayerPayload(obj) {
                    if (!obj || typeof obj !== 'object') return;
                    try {
                        delete obj.adPlacements;
                        delete obj.playerAds;
                        delete obj.adSlots;
                        delete obj.adBreakHeartbeatParams;
                        delete obj.reelPlayerParams;
                        delete obj.midrollInfo;
                        delete obj.adBreakHeartbeatParams;
                        delete obj.interstitials;
                        if (obj.playerResponse && typeof obj.playerResponse === 'object') {
                            cleanPlayerPayload(obj.playerResponse);
                        }
                        if (obj.playbackTracking) {
                            obj.playbackTracking.videostatsPlaybackUrl = { baseUrl: '' };
                            obj.playbackTracking.videostatsDelayplayUrl = { baseUrl: '' };
                            obj.playbackTracking.videostatsWatchtimeUrl = { baseUrl: '' };
                            obj.playbackTracking.qoeUrl = { baseUrl: '' };
                            obj.playbackTracking.atrUrl = { baseUrl: '' };
                        }
                    } catch(_) {}
                }

                var _ytInitialPlayerResp = undefined;
                try {
                    Object.defineProperty(window, 'ytInitialPlayerResponse', {
                        get: function() { return _ytInitialPlayerResp; },
                        set: function(val) {
                            cleanPlayerPayload(val);
                            _ytInitialPlayerResp = val;
                        },
                        configurable: true
                    });
                } catch(_) {}

                // 4. Runtime Fetch & XHR Response Pruning
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

                        var isPlayerReq = url && (url.indexOf('/youtubei/v1/player') !== -1 || url.indexOf('v1/player') !== -1);
                        var p = origFetch.apply(this, arguments);
                        if (isPlayerReq) {
                            return p.then(function(res) {
                                var origJson = res.json;
                                res.json = function() {
                                    return origJson.apply(this, arguments).then(function(data) {
                                        cleanPlayerPayload(data);
                                        return data;
                                    });
                                };
                                var origText = res.text;
                                res.text = function() {
                                    return origText.apply(this, arguments).then(function(text) {
                                        try {
                                            if (text && (text.indexOf('adPlacements') !== -1 || text.indexOf('playerAds') !== -1)) {
                                                var parsed = JSON.parse(text);
                                                cleanPlayerPayload(parsed);
                                                return JSON.stringify(parsed);
                                            }
                                        } catch(_) {}
                                        return text;
                                    });
                                };
                                return res;
                            });
                        }
                        return p;
                    };
                } catch(_) {}

                try {
                    var origXhrOpen = XMLHttpRequest.prototype.open;
                    var origXhrSend = XMLHttpRequest.prototype.send;
                    XMLHttpRequest.prototype.open = function(method, url) {
                        this.__hazeYtUrl = url || '';
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

                        if (url && (url.indexOf('/youtubei/v1/player') !== -1 || url.indexOf('v1/player') !== -1)) {
                            var self = this;
                            var origOnLoad = self.onload;
                            self.onload = function() {
                                try {
                                    if (self.responseText && (self.responseText.indexOf('adPlacements') !== -1 || self.responseText.indexOf('playerAds') !== -1)) {
                                        var json = JSON.parse(self.responseText);
                                        cleanPlayerPayload(json);
                                        var cleaned = JSON.stringify(json);
                                        Object.defineProperty(self, 'responseText', { get: function() { return cleaned; }, configurable: true });
                                        Object.defineProperty(self, 'response', { get: function() { return cleaned; }, configurable: true });
                                    }
                                } catch(_) {}
                                if (origOnLoad) return origOnLoad.apply(this, arguments);
                            };
                        }
                        return origXhrSend.apply(this, arguments);
                    };
                } catch(_) {}

                // 5. ytcfg experiment flags to disable anti-adblock modals & banners
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

                // 6. Automatic Dismissal of Enforcement Dialogs, Paywalls & Premium Promos
                function dismissPaywallsAndEnforcements() {
                    try {
                        var enforcementModals = document.querySelectorAll(
                            'ytd-enforcement-message-view-model, #enforcement-message, tp-yt-paper-dialog:has(ytd-enforcement-message-view-model), tp-yt-paper-dialog:has(#feedback), yt-playability-error-supported-renderers, ytm-mealbar-promo-renderer, ytm-upsell-dialog-renderer, ytd-mealbar-promo-renderer, ytd-popup-container yt-confirm-dialog-renderer'
                        );
                        for (var i = 0; i < enforcementModals.length; i++) {
                            enforcementModals[i].remove();
                        }
                        var backdrops = document.querySelectorAll('tp-yt-iron-overlay-backdrop, .opened[aria-hidden="true"]');
                        for (var b = 0; b < backdrops.length; b++) {
                            backdrops[b].remove();
                        }
                        var video = document.querySelector('video');
                        if (video && video.paused && !video.ended && video.readyState > 2) {
                            video.play().catch(function() {});
                        }
                    } catch(_) {}
                }

                // 7. Instant 0ms Video Ad Neutralizer & Skip Execution
                var adSelectors = [
                    '.ytp-ad-skip-button',
                    '.ytp-ad-skip-button-modern',
                    '.ytp-ad-overlay-close-button',
                    '.ytp-skip-ad-button',
                    '.ytm-skip-ad-button',
                    'button.ytp-ad-skip-button',
                    '.ytp-ad-skip-button-slot button',
                    '[id*="ad-skip"]',
                    'button[class*="skip-button"]',
                    '.videoAdUiSkipButton',
                    '.ytm-ad-overlay-close-button',
                    'button.ytm-ad-skip-button',
                    '.ytp-ad-text.ytp-ad-preview-text'
                ];

                var wasAdActive = false;

                function skipYouTubeAd() {
                    dismissPaywallsAndEnforcements();
                    var adShowing = document.querySelector('.ad-showing, .ad-interrupting, .video-ads.ad-showing, [class*="ad-showing"], [class*="ad-interrupting"]');
                    var video = document.querySelector('video');

                    if (adShowing) {
                        wasAdActive = true;
                        // 1. Instantly trigger skip buttons if available
                        for (var i = 0; i < adSelectors.length; i++) {
                            var btn = document.querySelector(adSelectors[i]);
                            if (btn) {
                                btn.click();
                                break;
                            }
                        }
                        // 2. Fast-forward video ad seamlessly
                        if (video) {
                            video.muted = true;
                            if (isFinite(video.duration) && video.duration > 0) {
                                video.currentTime = video.duration - 0.05;
                            } else {
                                video.currentTime = 99999;
                            }
                            video.playbackRate = 16;
                            // Trigger modern YouTube player API skip if present
                            try {
                                var player = document.getElementById('movie_player') || document.querySelector('.html5-video-player');
                                if (player && typeof player.stopVideo === 'function' && typeof player.nextVideo === 'function') {
                                    // Let player proceed
                                }
                            } catch(_) {}
                            video.play().catch(function() {});
                        }
                    } else {
                        if (wasAdActive) {
                            wasAdActive = false;
                            if (video) {
                                video.playbackRate = 1;
                                video.muted = false;
                                if (video.paused && !video.ended) {
                                    video.play().catch(function() {});
                                }
                            }
                        } else if (video && video.playbackRate > 1 && !adShowing) {
                            video.playbackRate = 1;
                            video.muted = false;
                        }
                    }

                    var dismissButtons = document.querySelectorAll('yt-button-renderer[aria-label="No thanks"], .yt-spec-button-shape-next[aria-label="No thanks"], ytm-mealbar-promo-renderer button, .ytp-ad-overlay-close-button');
                    for (var d = 0; d < dismissButtons.length; d++) {
                        try { dismissButtons[d].click(); } catch(_) {}
                    }
                }

                // 8. Automatic Fallback for Empty Home Feed
                function checkAndPopulateFeed() {
                    try {
                        var path = window.location.pathname;
                        if (path === '/' || path === '') {
                            var text = document.body ? document.body.innerText : '';
                            if (text && (text.indexOf('Try searching to get started') !== -1 || text.indexOf('build a feed of videos') !== -1)) {
                                var trendingBtn = document.querySelector('a[href*="/feed/trending"], a[href*="/feed/explore"], [aria-label*="Explore"], [aria-label*="Trending"]');
                                if (trendingBtn) {
                                    trendingBtn.click();
                                } else {
                                    window.location.replace('/feed/trending');
                                }
                            }
                        }
                    } catch(_) {}
                }

                if (document.readyState === 'complete' || document.readyState === 'interactive') {
                    setTimeout(checkAndPopulateFeed, 350);
                } else {
                    window.addEventListener('DOMContentLoaded', function() { setTimeout(checkAndPopulateFeed, 350); });
                }
                var feedObserver = new MutationObserver(function() {
                    checkAndPopulateFeed();
                });
                if (document.body) {
                    feedObserver.observe(document.body, { childList: true, subtree: true });
                } else {
                    window.addEventListener('DOMContentLoaded', function() {
                        if (document.body) feedObserver.observe(document.body, { childList: true, subtree: true });
                    });
                }

                // High-frequency 50ms check during video startup for zero-lag instant playback
                setInterval(skipYouTubeAd, 50);
                document.addEventListener('yt-navigate-finish', function() { skipYouTubeAd(); });
                document.addEventListener('yt-page-data-updated', function() { skipYouTubeAd(); });
                document.addEventListener('click', function() { setTimeout(skipYouTubeAd, 0); }, true);

                var observer = new MutationObserver(function() {
                    skipYouTubeAd();
                });
                observer.observe(document.documentElement, {
                    childList: true,
                    subtree: true,
                    attributes: true,
                    attributeFilter: ['class']
                });
                skipYouTubeAd();
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
                        var labelCandidates = document.querySelectorAll('span, p, small, header, h5, h6');
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

                var collapseTimer = null;
                var observer = new MutationObserver(function() {
                    if (!collapseTimer) {
                        collapseTimer = setTimeout(function() {
                            collapseTimer = null;
                            cleanAdSections();
                        }, 250);
                    }
                });

                var rootToObserve = document.documentElement || document.body;
                if (rootToObserve) {
                    observer.observe(rootToObserve, { childList: true, subtree: true });
                } else {
                    document.addEventListener('DOMContentLoaded', function() {
                        var target = document.documentElement || document.body;
                        if (target) observer.observe(target, { childList: true, subtree: true });
                    });
                }
            })();
        """.trimIndent()
    }
}
