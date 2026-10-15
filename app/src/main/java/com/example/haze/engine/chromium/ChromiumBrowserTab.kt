package com.example.haze.engine.chromium

import android.annotation.SuppressLint
import android.content.Context
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
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            allowFileAccess = false
            allowContentAccess = false
            mediaPlaybackRequiresUserGesture = false
            javaScriptCanOpenWindowsAutomatically = false
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
                offscreenPreRaster = false
            }
        }

        if (config.enableHardwareAcceleration) {
            webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        }
        webView.overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        webView.isVerticalScrollBarEnabled = false
        webView.isHorizontalScrollBarEnabled = false
    }

    private val defaultMobileUserAgent: String by lazy {
        WebSettings.getDefaultUserAgent(webView.context)
    }

    private fun applyDesktopMode(desktop: Boolean) {
        val currentUrl = webView.url ?: url
        val isSpotify = currentUrl.contains("spotify.com", ignoreCase = true)
        
        // Temporarily pause timers to prevent jank during reload
        webView.pauseTimers()
        
        if (desktop) {
            webView.settings.userAgentString = if (isSpotify) SPOTIFY_DESKTOP_UA else DESKTOP_USER_AGENT
            webView.settings.useWideViewPort = true
            webView.settings.loadWithOverviewMode = true
            // Setting minimum layout width helps trigger desktop layouts
            webView.settings.minimumLogicalFontSize = 14
        } else {
            webView.settings.userAgentString = defaultMobileUserAgent
            webView.settings.useWideViewPort = false
            webView.settings.loadWithOverviewMode = false
            webView.settings.minimumLogicalFontSize = 8
        }
        
        // Do not forcefully inject viewport sizes as a string. Instead, use a cleaner, non-intrusive approach. 
        // Forcefully overriding the viewport tag causes YouTube and other complex web apps to shrink improperly on mobile devices in desktop mode.
        val jsViewportFix = if (desktop) {
            """
            (function() {
                var meta = document.querySelector('meta[name="viewport"]');
                if (meta) {
                    meta.setAttribute('content', 'width=1280');
                } else {
                    meta = document.createElement('meta');
                    meta.name = 'viewport';
                    meta.content = 'width=1280';
                    (document.head || document.documentElement).appendChild(meta);
                }
            })();
            """.trimIndent()
        } else {
            """
            (function() {
                var meta = document.querySelector('meta[name="viewport"]');
                if (meta) {
                    meta.setAttribute('content', 'width=device-width, initial-scale=1.0');
                }
            })();
            """.trimIndent()
        }
        
        webView.evaluateJavascript(jsViewportFix) {
            navigationController.reload()
            // Resume timers slightly after reload initiates
            webView.postDelayed({ webView.resumeTimers() }, 100)
        }
    }

    private fun setupClientHandlers() {
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, pageUrl: String?, faviconBitmap: Bitmap?) {
                val nonNullUrl = pageUrl ?: ""
                url = nonNullUrl
                if (!nonNullUrl.startsWith("haze://") && !nonNullUrl.startsWith("about:")) {
                    val isSpotify = nonNullUrl.contains("spotify.com", ignoreCase = true)
                    val isYouTube = nonNullUrl.contains("youtube.com", ignoreCase = true)
                    if (isYouTube) {
                        view?.evaluateJavascript(YOUTUBE_ENGINE_SCRIPT, null)
                    }
                    if (isSpotify) {
                        try {
                            android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
                        } catch (_: Throwable) {}
                        view?.settings?.userAgentString = if (isDesktop) SPOTIFY_DESKTOP_UA else defaultMobileUserAgent
                        view?.settings?.useWideViewPort = isDesktop
                        view?.settings?.loadWithOverviewMode = isDesktop
                        view?.evaluateJavascript(SPOTIFY_ENGINE_SCRIPT, null)
                    } else if (isDesktop) {
                        view?.settings?.userAgentString = DESKTOP_USER_AGENT
                        view?.settings?.useWideViewPort = true
                        view?.settings?.loadWithOverviewMode = true
                        view?.evaluateJavascript(DESKTOP_VIEWPORT_SCRIPT, null)
                    } else {
                        webView.settings.userAgentString = defaultMobileUserAgent
                        view?.settings?.useWideViewPort = false
                        view?.settings?.loadWithOverviewMode = false
                        view?.evaluateJavascript(MOBILE_VIEWPORT_SCRIPT, null)
                    }
                    val script = com.example.haze.HazeAntiTrackingEngine.generateAntiFingerprintScript(webView.context)
                    view?.evaluateJavascript(script, null)

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
                }
                observer?.onPageStarted(this@ChromiumBrowserTab, nonNullUrl, faviconBitmap)
            }

            override fun onPageFinished(view: WebView?, pageUrl: String?) {
                val nonNullUrl = pageUrl ?: ""
                url = nonNullUrl
                title = view?.title ?: title
                val isSpotify = nonNullUrl.contains("spotify.com", ignoreCase = true)
                val isYouTube = nonNullUrl.contains("youtube.com", ignoreCase = true)
                if (isYouTube) {
                    view?.evaluateJavascript(YOUTUBE_ENGINE_SCRIPT, null)
                }
                if (isSpotify) {
                    view?.evaluateJavascript(SPOTIFY_ENGINE_SCRIPT, null)
                } else if (isDesktop) {
                    view?.evaluateJavascript(DESKTOP_VIEWPORT_SCRIPT, null)
                } else {
                    view?.evaluateJavascript(MOBILE_VIEWPORT_SCRIPT, null)
                }
                if (!nonNullUrl.startsWith("haze://") && !nonNullUrl.startsWith("about:")) {
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
                return null
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                var targetUrl = request?.url?.toString() ?: return false
                val ctx = webView.context
                if (com.example.haze.HazeAntiTrackingEngine.isBounceTrackingProtectionEnabled(ctx)) {
                    val unwrapped = com.example.haze.HazeAntiTrackingEngine.unwrapBounceTrackingUrl(targetUrl, ctx)
                    if (unwrapped != null) {
                        targetUrl = unwrapped
                        view?.loadUrl(targetUrl)
                        return true
                    }
                }
                if (com.example.haze.HazeAntiTrackingEngine.isLinkTrackingProtectionEnabled(ctx)) {
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

                    if (shieldActive && blockPopups && !isUserGesture) {
                        // Suppress unsolicited background popup/popunder!
                        HazePrivacyShieldEngine.recordPopupBlocked(ctx, url, url, isPrivate)
                        return false
                    }
                }

                val transport = resultMsg?.obj as? WebView.WebViewTransport
                if (transport != null) {
                    // Let the observer handle new window / tab opening
                    val handled = observer?.onNewWindowRequested(url, isUserGesture, isDialog) ?: false
                    return handled
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
        const val DESKTOP_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"
        const val SPOTIFY_DESKTOP_UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"

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

                // 3. Dedicated Direct Audio Player Engine
                window.__hazeAudioPlayer = window.__hazeAudioPlayer || new Audio();
                window.__hazeCurrentTrackId = null;
                window.__hazeCurrentTrackTitle = '';
                window.__hazeCurrentTrackArtist = '';
                window.__hazeCurrentTrackImg = '';

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

                function createOrUpdateFloatingPlayer() {
                    if (!document.body) return null;
                    var playerDiv = document.getElementById('__haze_floating_player');
                    if (!playerDiv) {
                        playerDiv = document.createElement('div');
                        playerDiv.id = '__haze_floating_player';
                        playerDiv.style.cssText = 'position:fixed;bottom:12px;left:12px;right:12px;background:#181818;border:1px solid #333;border-radius:12px;box-shadow:0 8px 32px rgba(0,0,0,0.85);z-index:9999999;display:none;flex-direction:column;padding:8px 12px;font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif;color:#fff;user-select:none;';
                        playerDiv.innerHTML = '<div style="display:flex;align-items:center;justify-content:space-between;width:100%;"><div style="display:flex;align-items:center;flex:1;overflow:hidden;margin-right:10px;"><img id="__haze_fp_img" style="width:40px;height:40px;border-radius:6px;object-fit:cover;margin-right:10px;background:#282828;display:none;" /><div style="display:flex;flex-direction:column;overflow:hidden;"><span id="__haze_fp_title" style="font-size:13px;font-weight:700;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;">Spotify</span><span id="__haze_fp_artist" style="font-size:11px;color:#b3b3b3;white-space:nowrap;overflow:hidden;text-overflow:ellipsis;">Direct Play</span></div></div><div style="display:flex;align-items:center;gap:12px;"><button id="__haze_fp_prev" style="background:none;border:none;color:#b3b3b3;cursor:pointer;padding:4px;display:flex;align-items:center;"><svg width="20" height="20" viewBox="0 0 24 24" fill="currentColor"><path d="M6 6h2v12H6zm3.5 6l8.5 6V6z"/></svg></button><button id="__haze_fp_playpause" style="background:#1db954;border:none;border-radius:50%;width:36px;height:36px;color:#000;display:flex;align-items:center;justify-content:center;cursor:pointer;"><svg id="__haze_fp_icon_play" width="20" height="20" viewBox="0 0 24 24" fill="currentColor" style="display:none;"><path d="M8 5v14l11-7z"/></svg><svg id="__haze_fp_icon_pause" width="20" height="20" viewBox="0 0 24 24" fill="currentColor"><path d="M6 19h4V5H6v14zm8-14v14h4V5h-4z"/></svg></button><button id="__haze_fp_next" style="background:none;border:none;color:#b3b3b3;cursor:pointer;padding:4px;display:flex;align-items:center;"><svg width="20" height="20" viewBox="0 0 24 24" fill="currentColor"><path d="M6 18l8.5-6L6 6v12zM16 6v12h2V6h-2z"/></svg></button></div></div><div style="display:flex;align-items:center;gap:8px;margin-top:6px;width:100%;"><span id="__haze_fp_time" style="font-size:10px;color:#b3b3b3;min-width:28px;">0:00</span><div id="__haze_fp_prog_bar" style="flex:1;height:4px;background:#404040;border-radius:2px;cursor:pointer;position:relative;"><div id="__haze_fp_prog_fill" style="width:0%;height:100%;background:#1db954;border-radius:2px;"></div></div><span id="__haze_fp_dur" style="font-size:10px;color:#b3b3b3;min-width:28px;">0:30</span></div>';
                        document.body.appendChild(playerDiv);

                        document.getElementById('__haze_fp_playpause').onclick = function(ev) {
                            ev.stopPropagation();
                            if (window.__hazeAudioPlayer) {
                                if (window.__hazeAudioPlayer.paused) {
                                    window.__hazeAudioPlayer.play().catch(function(_) {});
                                    updateHazePlayerState(true);
                                } else {
                                    window.__hazeAudioPlayer.pause();
                                    updateHazePlayerState(false);
                                }
                            }
                        };
                        document.getElementById('__haze_fp_prev').onclick = function(ev) {
                            ev.stopPropagation();
                            if (window.__hazeAudioPlayer) window.__hazeAudioPlayer.currentTime = 0;
                        };
                        document.getElementById('__haze_fp_next').onclick = function(ev) {
                            ev.stopPropagation();
                            playNextTrack();
                        };
                        document.getElementById('__haze_fp_prog_bar').onclick = function(ev) {
                            ev.stopPropagation();
                            if (window.__hazeAudioPlayer && window.__hazeAudioPlayer.duration) {
                                var rect = this.getBoundingClientRect();
                                var pos = (ev.clientX - rect.left) / rect.width;
                                window.__hazeAudioPlayer.currentTime = pos * window.__hazeAudioPlayer.duration;
                            }
                        };
                    }
                    return playerDiv;
                }

                function updateHazePlayerUI() {
                    var playerDiv = createOrUpdateFloatingPlayer();
                    if (!playerDiv) return;
                    playerDiv.style.display = 'flex';
                    var imgEl = document.getElementById('__haze_fp_img');
                    if (imgEl) {
                        if (window.__hazeCurrentTrackImg) {
                            imgEl.src = window.__hazeCurrentTrackImg;
                            imgEl.style.display = 'block';
                        } else {
                            imgEl.style.display = 'none';
                        }
                    }
                    var titleEl = document.getElementById('__haze_fp_title');
                    if (titleEl) titleEl.textContent = window.__hazeCurrentTrackTitle || 'Playing Track';
                    var artistEl = document.getElementById('__haze_fp_artist');
                    if (artistEl) artistEl.textContent = window.__hazeCurrentTrackArtist || 'Spotify';

                    var npTitle = document.querySelector('[data-testid="context-item-info-title"]');
                    if (npTitle) npTitle.textContent = window.__hazeCurrentTrackTitle;
                    var npArtist = document.querySelector('[data-testid="context-item-info-subtitles"]');
                    if (npArtist) npArtist.textContent = window.__hazeCurrentTrackArtist;
                }

                function updateHazePlayerState(isPlaying) {
                    var iconPlay = document.getElementById('__haze_fp_icon_play');
                    var iconPause = document.getElementById('__haze_fp_icon_pause');
                    if (iconPlay && iconPause) {
                        iconPlay.style.display = isPlaying ? 'none' : 'block';
                        iconPause.style.display = isPlaying ? 'block' : 'none';
                    }
                }

                window.__hazeAudioPlayer.ontimeupdate = function() {
                    var cur = window.__hazeAudioPlayer.currentTime || 0;
                    var dur = window.__hazeAudioPlayer.duration || 30;
                    var pct = Math.min(100, Math.max(0, (cur / dur) * 100));
                    var fill = document.getElementById('__haze_fp_prog_fill');
                    if (fill) fill.style.width = pct + '%';

                    var timeEl = document.getElementById('__haze_fp_time');
                    if (timeEl) {
                        var m = Math.floor(cur / 60);
                        var s = Math.floor(cur % 60);
                        timeEl.textContent = m + ':' + (s < 10 ? '0' : '') + s;
                    }
                    var durEl = document.getElementById('__haze_fp_dur');
                    if (durEl && dur > 0) {
                        var dm = Math.floor(dur / 60);
                        var ds = Math.floor(dur % 60);
                        durEl.textContent = dm + ':' + (ds < 10 ? '0' : '') + ds;
                    }
                };

                window.__hazeAudioPlayer.onended = function() {
                    updateHazePlayerState(false);
                    playNextTrack();
                };

                function playNextTrack() {
                    var rows = Array.from(document.querySelectorAll('[data-testid*="track"], [data-testid*="row"], [role="row"], .track-item, li'));
                    if (!rows.length) return;
                    var currentIndex = -1;
                    for (var i = 0; i < rows.length; i++) {
                        var r = rows[i];
                        var link = r.querySelector ? r.querySelector('a[href*="/track/"]') : null;
                        if (link && link.href.indexOf(window.__hazeCurrentTrackId) !== -1) {
                            currentIndex = i;
                            break;
                        }
                    }
                    var nextRow = rows[currentIndex + 1] || rows[0];
                    if (nextRow) {
                        var info = findTrackTarget(nextRow);
                        if (info && info.trackId) hazePlayTrack(info.trackId, info.title, info.artist, info.img);
                    }
                }

                function hazePlayTrack(trackId, title, artist, img) {
                    if (!trackId) return;

                    if (window.__hazeCurrentTrackId === trackId && window.__hazeAudioPlayer.src) {
                        if (window.__hazeAudioPlayer.paused) {
                            window.__hazeAudioPlayer.play().catch(function(_) {});
                            updateHazePlayerState(true);
                        } else {
                            window.__hazeAudioPlayer.pause();
                            updateHazePlayerState(false);
                        }
                        return;
                    }

                    window.__hazeCurrentTrackId = trackId;
                    window.__hazeCurrentTrackTitle = title || 'Playing Track';
                    window.__hazeCurrentTrackArtist = artist || '';
                    window.__hazeCurrentTrackImg = img || '';

                    // Stop and mute all other native audio elements
                    var otherAudios = document.querySelectorAll('audio');
                    for (var a = 0; a < otherAudios.length; a++) {
                        if (otherAudios[a] !== window.__hazeAudioPlayer) {
                            otherAudios[a].pause();
                            otherAudios[a].muted = true;
                            otherAudios[a].src = '';
                        }
                    }

                    dismissSpotifyModals();
                    updateHazePlayerUI();
                    updateHazePlayerState(true);

                    // Fetch Spotify embed track preview stream
                    var embedUrl = 'https://open.spotify.com/embed/track/' + trackId;
                    fetch(embedUrl)
                        .then(function(res) { return res.text(); })
                        .then(function(html) {
                            var audioUrl = null;
                            var nextDataMatch = html.match(/<script id="__NEXT_DATA__" type="application\/json">([^<]+)<\/script>/);
                            if (nextDataMatch && nextDataMatch[1]) {
                                try {
                                    var parsed = JSON.parse(nextDataMatch[1]);
                                    var entity = parsed.props?.pageProps?.state?.data?.entity;
                                    if (entity) {
                                        audioUrl = entity.audioPreview?.url;
                                        if ((!title || title === 'Playing Track') && entity.name) {
                                            window.__hazeCurrentTrackTitle = entity.name;
                                        }
                                        if (!artist && entity.artists && entity.artists[0]) {
                                            window.__hazeCurrentTrackArtist = entity.artists[0].name;
                                        }
                                        if (!img && entity.visualIdentity?.image && entity.visualIdentity.image[0]) {
                                            window.__hazeCurrentTrackImg = entity.visualIdentity.image[0].url;
                                        }
                                    }
                                } catch(_) {}
                            }
                            if (!audioUrl) {
                                var m = html.match(/"audioPreview":\s*\{\s*"url":\s*"([^"]+)"/);
                                if (m && m[1]) audioUrl = m[1].replace(/\\u002F/g, '/');
                            }
                            if (!audioUrl) {
                                var m2 = html.match(/https:[^"'\\]+mp3-preview[^"'\\]+/);
                                if (m2 && m2[0]) audioUrl = m2[0].replace(/\\u002F/g, '/');
                            }

                            if (audioUrl) {
                                window.__hazeAudioPlayer.src = audioUrl;
                                window.__hazeAudioPlayer.muted = false;
                                window.__hazeAudioPlayer.play().catch(function(_) {});
                                updateHazePlayerUI();
                                updateHazePlayerState(true);
                            }
                        })
                        .catch(function(_) {});
                }

                // 4. Robust Track Extraction
                function findTrackTarget(target) {
                    if (!target) return null;
                    if (target.closest && target.closest('#__haze_floating_player')) return null;
                    if (target.closest && target.closest('button[data-testid="more-button"], button[aria-haspopup="menu"], button[data-testid="add-button"], button[aria-label*="Save" i], button[aria-label*="heart" i], button[aria-label*="More" i]')) return null;

                    var isTrackPage = window.location.pathname.indexOf('/track/') !== -1;
                    var playBtn = target.closest ? target.closest('button[data-testid="play-button"], button[data-testid="action-bar-play-button"], [data-testid="action-bar-row"] button') : null;
                    if (playBtn && isTrackPage) {
                        var m = window.location.pathname.match(/\/track\/([a-zA-Z0-9]+)/);
                        if (m) {
                            var title = document.querySelector('h1')?.textContent?.trim() || '';
                            var artist = document.querySelector('a[href*="/artist/"], [data-testid="creator-link"]')?.textContent?.trim() || '';
                            var img = document.querySelector('img[data-testid="entity-image"], img')?.src || '';
                            return { trackId: m[1], title: title, artist: artist, img: img };
                        }
                    }

                    var trackLink = target.closest ? target.closest('a[href*="/track/"]') : null;
                    var row = target.closest ? target.closest('[data-testid*="track"], [data-testid*="row"], [role="row"], tr.tracklist-row, .track-item, [data-uri*="spotify:track:"], li[role="listitem"], [data-testid="top-result-card"], [data-testid="heropod"]') : null;

                    var el = trackLink || row;
                    if (!el && target) {
                        if (target.querySelector && target.querySelector('a[href*="/track/"]')) {
                            el = target;
                        } else {
                            var parentWithLink = target.parentElement;
                            for (var p = 0; p < 4 && parentWithLink; p++) {
                                if (parentWithLink.querySelector && parentWithLink.querySelector('a[href*="/track/"]')) {
                                    el = parentWithLink;
                                    break;
                                }
                                parentWithLink = parentWithLink.parentElement;
                            }
                        }
                    }

                    if (!el) return null;

                    var link = el.querySelector ? el.querySelector('a[href*="/track/"]') : null;
                    if (!link && trackLink) link = trackLink;
                    var trackId = null;
                    if (link && link.href) {
                        var m = link.href.match(/\/track\/([a-zA-Z0-9]+)/);
                        if (m) trackId = m[1];
                    }
                    if (!trackId && el.getAttribute) {
                        var uri = el.getAttribute('data-uri') || '';
                        var m = uri.match(/spotify:track:([a-zA-Z0-9]+)/);
                        if (m) trackId = m[1];
                    }
                    if (!trackId && isTrackPage) {
                        var m = window.location.pathname.match(/\/track\/([a-zA-Z0-9]+)/);
                        if (m) trackId = m[1];
                    }

                    if (!trackId) return null;

                    var title = (el.querySelector && el.querySelector('[dir="auto"], [data-testid="internal-track-link"], div[class*="Title"], a[href*="/track/"]')?.textContent?.trim()) || (trackLink ? trackLink.textContent?.trim() : '') || 'Playing Track';
                    var artist = (el.querySelector && el.querySelector('a[href*="/artist/"], span[class*="Artist"]')?.textContent?.trim()) || '';
                    var img = (el.querySelector && el.querySelector('img')?.src) || '';

                    return { trackId: trackId, title: title, artist: artist, img: img };
                }

                // Capture-phase Touch & Click Interceptor (Prevents Spotify from launching Shuffle mode)
                var lastHandledTime = 0;
                var lastHandledTrackId = null;

                function handleTrackInteraction(e) {
                    try {
                        var target = e.target;
                        if (!target) return;
                        var info = findTrackTarget(target);
                        if (info && info.trackId) {
                            e.preventDefault();
                            e.stopPropagation();
                            e.stopImmediatePropagation();

                            var now = Date.now();
                            if (now - lastHandledTime < 500 && lastHandledTrackId === info.trackId) {
                                return;
                            }
                            lastHandledTime = now;
                            lastHandledTrackId = info.trackId;

                            hazePlayTrack(info.trackId, info.title, info.artist, info.img);
                        }
                    } catch(_) {}
                }

                window.addEventListener('pointerdown', handleTrackInteraction, true);
                document.addEventListener('pointerdown', handleTrackInteraction, true);
                window.addEventListener('touchstart', handleTrackInteraction, { capture: true, passive: false });
                document.addEventListener('touchstart', handleTrackInteraction, { capture: true, passive: false });
                window.addEventListener('click', handleTrackInteraction, true);
                document.addEventListener('click', handleTrackInteraction, true);

                // 5. Continuous Supervisor: Mute ads, dismiss modals, video playsinline
                function superviseSpotify() {
                    try {
                        dismissSpotifyModals();

                        var audios = document.querySelectorAll('audio');
                        for (var i = 0; i < audios.length; i++) {
                            var a = audios[i];
                            if (a === window.__hazeAudioPlayer) continue;
                            a.pause();
                            a.muted = true;
                        }

                        var videos = document.querySelectorAll('video');
                        for (var j = 0; j < videos.length; j++) {
                            var v = videos[j];
                            v.muted = true;
                            v.playsInline = true;
                            v.setAttribute('playsinline', '');
                            v.setAttribute('webkit-playsinline', '');
                            if (v.paused && v.readyState >= 2) {
                                v.play().catch(function(_) {});
                            }
                        }
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

                // 1. JSON Player Response Interceptor: Strip all ad placements and ad breaks from YouTube API
                function cleanPlayerJson(data) {
                    if (!data || typeof data !== 'object') return data;
                    try {
                        if (data.adPlacements) delete data.adPlacements;
                        if (data.playerAds) delete data.playerAds;
                        if (data.adSlots) delete data.adSlots;
                        if (data.adBreakHeartbeatParams) delete data.adBreakHeartbeatParams;
                        if (data.playerResponse) cleanPlayerJson(data.playerResponse);
                    } catch(_) {}
                    return data;
                }

                try {
                    var origFetch = window.fetch;
                    window.fetch = function(input, init) {
                        var url = (typeof input === 'string') ? input : (input && input.url ? input.url : '');
                        var isPlayerReq = url && (url.indexOf('/youtubei/v1/player') !== -1 || url.indexOf('v1/player') !== -1 || url.indexOf('/youtubei/v1/next') !== -1);
                        if (isPlayerReq) {
                            return origFetch.apply(this, arguments).then(function(res) {
                                var clone = res.clone();
                                return clone.json().then(function(json) {
                                    cleanPlayerJson(json);
                                    var modifiedStr = JSON.stringify(json);
                                    return new Response(modifiedStr, {
                                        status: res.status,
                                        statusText: res.statusText,
                                        headers: res.headers
                                    });
                                }).catch(function() {
                                    return res;
                                });
                            });
                        }
                        return origFetch.apply(this, arguments);
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
                        if (url && (url.indexOf('/youtubei/v1/player') !== -1 || url.indexOf('v1/player') !== -1 || url.indexOf('/youtubei/v1/next') !== -1)) {
                            var self = this;
                            var origOnLoad = self.onload;
                            self.onload = function() {
                                try {
                                    if (self.responseText && self.responseText.indexOf('adPlacements') !== -1) {
                                        var json = JSON.parse(self.responseText);
                                        cleanPlayerJson(json);
                                        Object.defineProperty(self, 'responseText', { get: function() { return JSON.stringify(json); }, configurable: true });
                                        Object.defineProperty(self, 'response', { get: function() { return JSON.stringify(json); }, configurable: true });
                                    }
                                } catch(_) {}
                                if (origOnLoad) return origOnLoad.apply(this, arguments);
                            };
                        }
                        return origXhrSend.apply(this, arguments);
                    };
                } catch(_) {}

                // 2. High-performance Video Ad Skipper & Auto-Fast-Forwarder
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
                    'button.ytm-ad-skip-button'
                ];

                function handleYtPlayerVisibility() {
                    try {
                        var isWatch = window.location.pathname.indexOf('/watch') !== -1 ||
                                      window.location.pathname.indexOf('/shorts') !== -1 ||
                                      window.location.search.indexOf('v=') !== -1;
                        var containers = document.querySelectorAll('#player-container-id, .player-container');
                        for (var k = 0; k < containers.length; k++) {
                            var c = containers[k];
                            var activeVideo = c.querySelector('video[src], video:not([src=""])');
                            if (!isWatch && (!activeVideo || activeVideo.paused)) {
                                c.style.setProperty('display', 'none', 'important');
                            } else if (isWatch) {
                                c.style.removeProperty('display');
                            }
                        }
                    } catch(e) {}
                }

                function skipYouTubeAd() {
                    handleYtPlayerVisibility();
                    var adShowing = document.querySelector('.ad-showing, .ad-interrupting, .video-ads.ad-showing, [class*="ad-showing"], [class*="ad-interrupting"]');
                    var video = document.querySelector('video');

                    if (adShowing) {
                        for (var i = 0; i < adSelectors.length; i++) {
                            var btn = document.querySelector(adSelectors[i]);
                            if (btn) {
                                btn.click();
                                break;
                            }
                        }
                        if (video) {
                            video.muted = true;
                            if (isFinite(video.duration) && video.duration > 0) {
                                video.currentTime = video.duration;
                            } else {
                                video.currentTime = 99999;
                            }
                            video.playbackRate = 16;
                            video.play().catch(function() {});
                        }
                    } else if (video && video.playbackRate > 1) {
                        video.playbackRate = 1;
                        video.muted = false;
                    }

                    var dismissButtons = document.querySelectorAll('yt-button-renderer[aria-label="No thanks"], .yt-spec-button-shape-next[aria-label="No thanks"], ytm-mealbar-promo-renderer button, .ytp-ad-overlay-close-button');
                    for (var d = 0; d < dismissButtons.length; d++) {
                        try { dismissButtons[d].click(); } catch(_) {}
                    }

                    var overlays = document.querySelectorAll(
                        '.ytp-ad-overlay-container, .ytp-ad-text, .ytp-ad-message-container, .ytp-ad-image-overlay, ytd-ad-slot-renderer, ytd-in-feed-ad-layout-renderer, ytm-promoted-sparkles-web-renderer, ytm-promoted-video-renderer, ytm-ad-slot-renderer, ytm-companion-ad-renderer, ytm-inline-ad, #masthead-ad, ytm-statement-banner-renderer, ytm-banner-promo-renderer, ytm-compact-promoted-video-renderer, #player-ads'
                    );
                    for (var j = 0; j < overlays.length; j++) {
                        overlays[j].style.setProperty('display', 'none', 'important');
                    }
                }

                handleYtPlayerVisibility();
                setInterval(skipYouTubeAd, 250);
                window.addEventListener('popstate', handleYtPlayerVisibility);
                document.addEventListener('yt-navigate-finish', function() { handleYtPlayerVisibility(); setTimeout(skipYouTubeAd, 50); });
                document.addEventListener('yt-page-data-updated', function() { handleYtPlayerVisibility(); setTimeout(skipYouTubeAd, 50); });
                document.addEventListener('click', function() {
                    setTimeout(skipYouTubeAd, 0);
                    setTimeout(handleYtPlayerVisibility, 150);
                }, true);

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
                if (window.__hazeBannerCleanerInjected) return;
                window.__hazeBannerCleanerInjected = true;

                // 1. Declarative CSS injection for all known ad containers, banners, slots, and placeholders
                try {
                    var s = document.getElementById('__haze_dynamic_banner_css');
                    if (!s) {
                        s = document.createElement('style');
                        s.id = '__haze_dynamic_banner_css';
                        s.type = 'text/css';
                        s.textContent = 'ins.adsbygoogle, iframe[id*="google_ads"], iframe[src*="doubleclick"], iframe[src*="googlesyndication"], iframe[src*="googleadservices"], iframe[src*="amazon-adsystem"], div[id*="google_ads"], div[id^="dfp-"], div[id^="gpt-"], div[id*="gpt-ad"], div[id*="ad-slot"], div[id*="ad-container"], div[id*="ad-wrapper"], div[id*="ad-banner"], div[id*="ad-placeholder"], div[id*="ad-section"], div[id*="ad-box"], div[id*="ad-holder"], div[class*="ad-slot"], div[class*="ad-container"], div[class*="ad-wrapper"], div[class*="ad-placeholder"], div[class*="ad-banner"], div[class*="ad-section"], div[class*="ad-box"], div[class*="ad-holder"], div[class*="ad_wrapper"], div[class*="ad_container"], div[class*="ad_banner"], div[class*="ad_slot"], div[class*="ad_placeholder"], .ad-banner, .banner-ad, .banner_ad, .ad-slot, .ad-box, .ad-container, .ad-wrapper, .ad-unit, .ad-holder, .ad-space, .ad-area, .ad-section, .ad-placeholder, .ad-label, .ad-notice, .ad-title, .ad-header, .advertisement, .advertisement-container, .advertisement-wrapper, .advertisement-banner, .advertisement-slot, .advertisement-box, .advertisement-section, .advertisement-placeholder, [data-ad-unit], [data-ad-slot], [data-ad-client], [data-ad-name], [data-ad-format], [data-ad-container], [data-ad], [data-ad-type], [aria-label="Advertisement"], [aria-label="Sponsored"], [aria-label="Advert"], [aria-label="Anuncio"], [data-advertisement], .sponsored-post, .sponsored-content, .promoted-content, .sponsored-badge, .header-ad, .footer-ad, .sidebar-ad, .top-ad, .bottom-ad, .inline-ad, .native-ad, .native_ad, .tbl-feed-container, .trc_rbox_div, div[id*="taboola"], .outbrain_widget, .OB-widget, div[id*="rc-widget"], .mgbox, .mgid-widget { display: none !important; height: 0 !important; max-height: 0 !important; min-height: 0 !important; width: 0 !important; max-width: 0 !important; min-width: 0 !important; margin: 0 !important; padding: 0 !important; border: none !important; outline: none !important; opacity: 0 !important; pointer-events: none !important; overflow: hidden !important; }';
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
                            strUrl.indexOf('ad-maven') !== -1 || strUrl.indexOf('clicksor') !== -1
                        )) {
                            return null;
                        }
                        return origWindowOpen.apply(this, arguments);
                    };
                } catch(_) {}

                // 3. Annoying Overlays & Cookie Consent Wall Dismissal
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

                // 4. DOM Section Collapser: Finds "Advertisement", "Sponsored", "Anuncio", or "Ad" badges and collapses wrapper sections!
                function collapseAdPlaceholdersAndSections() {
                    try {
                        dismissAnnoyingOverlays();

                        var walker = document.createTreeWalker(document.body || document.documentElement, NodeFilter.SHOW_TEXT, null, false);
                        var node;
                        var labelNodes = [];
                        while (node = walker.nextNode()) {
                            var text = (node.nodeValue || '').trim().toLowerCase();
                            if (text === 'advertisement' || text === 'sponsored' || text === 'anuncio' || text === 'publicité' || text === 'werbung' || text === 'advert' || text === 'ad') {
                                if (node.parentElement && node.parentElement.tagName !== 'SCRIPT' && node.parentElement.tagName !== 'STYLE') {
                                    labelNodes.push(node.parentElement);
                                }
                            }
                        }

                        for (var i = 0; i < labelNodes.length; i++) {
                            var el = labelNodes[i];
                            if (!el) continue;

                            var container = el;
                            for (var depth = 0; depth < 4; depth++) {
                                if (container.parentElement && container.parentElement !== document.body && container.parentElement !== document.documentElement) {
                                    var p = container.parentElement;
                                    var pClass = (p.className || '').toString().toLowerCase();
                                    var pId = (p.id || '').toString().toLowerCase();
                                    if (p.children.length <= 4 || pClass.indexOf('ad') !== -1 || pId.indexOf('ad') !== -1 || p.tagName === 'SECTION' || p.tagName === 'ASIDE' || p.tagName === 'DIV') {
                                        container = p;
                                    } else {
                                        break;
                                    }
                                }
                            }

                            if (container && container !== document.body && container !== document.documentElement) {
                                container.style.setProperty('display', 'none', 'important');
                                container.style.setProperty('height', '0px', 'important');
                                container.style.setProperty('max-height', '0px', 'important');
                                container.style.setProperty('min-height', '0px', 'important');
                                container.style.setProperty('margin', '0px', 'important');
                                container.style.setProperty('padding', '0px', 'important');
                                container.style.setProperty('opacity', '0', 'important');
                                container.style.setProperty('border', 'none', 'important');
                            }
                        }

                        var adIframes = document.querySelectorAll('iframe[src*="doubleclick"], iframe[src*="googlesyndication"], iframe[src*="googleadservices"], iframe[id*="google_ads"]');
                        for (var j = 0; j < adIframes.length; j++) {
                            var frame = adIframes[j];
                            try { frame.src = 'about:blank'; } catch(_) {}
                            frame.style.setProperty('display', 'none', 'important');
                            frame.style.setProperty('height', '0px', 'important');
                            frame.style.setProperty('margin', '0px', 'important');
                            frame.style.setProperty('padding', '0px', 'important');
                            var pFrame = frame.parentElement;
                            if (pFrame && pFrame !== document.body) {
                                pFrame.style.setProperty('display', 'none', 'important');
                                pFrame.style.setProperty('height', '0px', 'important');
                                pFrame.style.setProperty('margin', '0px', 'important');
                                pFrame.style.setProperty('padding', '0px', 'important');
                            }
                        }
                    } catch(_) {}
                }

                collapseAdPlaceholdersAndSections();
                if (document.readyState === 'loading') {
                    document.addEventListener('DOMContentLoaded', collapseAdPlaceholdersAndSections);
                }
                window.addEventListener('load', collapseAdPlaceholdersAndSections);

                var collapseTimer = null;
                var observer = new MutationObserver(function() {
                    if (!collapseTimer) {
                        collapseTimer = setTimeout(function() {
                            collapseTimer = null;
                            collapseAdPlaceholdersAndSections();
                        }, 300);
                    }
                });

                if (document.body) {
                    observer.observe(document.body, { childList: true, subtree: true });
                } else {
                    document.addEventListener('DOMContentLoaded', function() {
                        if (document.body) observer.observe(document.body, { childList: true, subtree: true });
                    });
                }
            })();
        """.trimIndent()
    }
}
