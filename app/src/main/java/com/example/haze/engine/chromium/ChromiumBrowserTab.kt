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
            defaultTextEncodingName = config.defaultEncoding

            // Chromium & Google Password Manager integration
            saveFormData = true
            savePassword = true

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                safeBrowsingEnabled = true
                webView.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_YES
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
        if (desktop) {
            webView.settings.userAgentString = if (isSpotify) SPOTIFY_DESKTOP_UA else DESKTOP_USER_AGENT
            webView.settings.useWideViewPort = true
            webView.settings.loadWithOverviewMode = true
        } else {
            webView.settings.userAgentString = defaultMobileUserAgent
            webView.settings.useWideViewPort = false
            webView.settings.loadWithOverviewMode = false
        }
        navigationController.reload()
    }

    private fun setupClientHandlers() {
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, pageUrl: String?, faviconBitmap: Bitmap?) {
                val nonNullUrl = pageUrl ?: ""
                url = nonNullUrl
                if (!nonNullUrl.startsWith("haze://") && !nonNullUrl.startsWith("about:")) {
                    val isSpotify = nonNullUrl.contains("spotify.com", ignoreCase = true)
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

                // 3. Direct Song Click-To-Play:
                // Tapping ANY song (in search results, album/playlist rows, or single-track action-bar)
                // directly triggers playback for THAT specific song!
                document.addEventListener('click', function(e) {
                    try {
                        var target = e.target;
                        if (!target) return;

                        // Allow menu (...) or save/heart buttons to function normally
                        if (target.closest('button[data-testid="more-button"], button[aria-haspopup="menu"], button[data-testid="add-button"], button[aria-label*="Save" i], button[aria-label*="heart" i], button[aria-label*="More" i]')) {
                            return;
                        }

                        // Case A: Single track page action-bar play button (open.spotify.com/track/...)
                        var isTrackPage = window.location.pathname.indexOf('/track/') !== -1;
                        var actionBarPlayBtn = target.closest('[data-testid="action-bar-row"] button[data-testid="play-button"], button[data-testid="play-button"], [data-testid="action-bar-play-button"]');
                        if (isTrackPage && actionBarPlayBtn) {
                            var rowInPage = document.querySelector('[data-testid="tracklist-row"], [data-testid="compact-track-row"], [role="row"]');
                            if (rowInPage) {
                                var rowPlay = rowInPage.querySelector('button[data-testid="play-button"], button[aria-label*="Play" i]');
                                if (rowPlay && rowPlay !== actionBarPlayBtn) {
                                    e.preventDefault();
                                    e.stopPropagation();
                                    e.stopImmediatePropagation();
                                    rowPlay.click();
                                    return;
                                }
                            }
                        }

                        // Case B: Any Tracklist row / search item
                        var row = target.closest('[data-testid="tracklist-row"], [data-testid="compact-track-row"], [data-testid="track-row"], [data-testid="playlist-track-row"], [data-testid="search-result-row"], [role="row"], tr.tracklist-row, .track-item, [data-uri*="spotify:track:"], li[role="listitem"]');
                        if (row) {
                            var trackLink = row.querySelector('a[href*="/track/"]');
                            var trackId = trackLink ? (trackLink.href.match(/\/track\/([a-zA-Z0-9]+)/) || [])[1] : null;
                            if (!trackId && window.location.pathname.indexOf('/track/') !== -1) {
                                trackId = (window.location.pathname.match(/\/track\/([a-zA-Z0-9]+)/) || [])[1];
                            }
                            var titleEl = row.querySelector('[dir="auto"], [data-testid="internal-track-link"], div[class*="Title"], a[href*="/track/"]');
                            var targetTitle = titleEl ? titleEl.textContent.trim() : (document.querySelector('h1')?.textContent?.trim() || '');

                            window.__hazeTargetTrack = { id: trackId, title: targetTitle, time: Date.now() };

                            var playBtn = row.querySelector('button[data-testid="play-button"], button[data-action-id="play-button"], button[aria-label*="Play" i], button[aria-label*="play" i], button[aria-label*="reproducir" i]');
                            if (playBtn) {
                                if (target !== playBtn && !playBtn.contains(target)) {
                                    e.preventDefault();
                                    e.stopPropagation();
                                    e.stopImmediatePropagation();

                                    try {
                                        playBtn.dispatchEvent(new MouseEvent('mouseover', { bubbles: true, cancelable: true }));
                                        playBtn.dispatchEvent(new MouseEvent('mouseenter', { bubbles: true, cancelable: true }));
                                    } catch(_) {}

                                    playBtn.click();
                                    return;
                                }
                            } else {
                                e.preventDefault();
                                e.stopPropagation();
                                e.stopImmediatePropagation();

                                var dbl = new MouseEvent('dblclick', { bubbles: true, cancelable: true, view: window });
                                row.dispatchEvent(dbl);
                                var clk = new MouseEvent('click', { bubbles: true, cancelable: true, view: window });
                                row.dispatchEvent(clk);
                            }
                        }
                    } catch(_) {}
                }, true);

                // 4. Continuous Session Supervisor: Auto-close popups, skip ads, and correct shuffle mismatches
                function superviseSpotify() {
                    try {
                        // Dismiss upsell & login modal popups
                        var popups = document.querySelectorAll('[data-testid="upsell-modal"], [data-testid="login-modal"], div[role="dialog"]');
                        for (var p = 0; p < popups.length; p++) {
                            var popup = popups[p];
                            var closeBtn = popup.querySelector('button[aria-label*="Close" i], button[aria-label*="close" i], [data-testid="close-button"], button[data-testid="dismiss-button"]');
                            if (closeBtn) closeBtn.click();
                        }
                        var cookieBtn = document.getElementById('onetrust-accept-btn-handler');
                        if (cookieBtn) cookieBtn.click();

                        // Detect Ad in Now Playing Widget
                        var nowPlaying = document.querySelector('[data-testid="now-playing-widget"]');
                        var isAd = false;
                        var currentSongTitle = '';
                        if (nowPlaying) {
                            if (nowPlaying.querySelector('[data-testid="ad-playback-indicator"]')) {
                                isAd = true;
                            }
                            var titleEl = nowPlaying.querySelector('[data-testid="context-item-info-title"]');
                            currentSongTitle = titleEl ? (titleEl.textContent || '').trim().toLowerCase() : '';
                            if (currentSongTitle === 'advertisement' || currentSongTitle === 'anuncio' || currentSongTitle === 'spotify' || currentSongTitle === 'ad') {
                                isAd = true;
                            }
                        }

                        // Auto-skip ad
                        if (isAd) {
                            var skipBtn = document.querySelector('[data-testid="control-button-skip-forward"]');
                            if (skipBtn && !skipBtn.disabled) {
                                skipBtn.click();
                            }
                        }

                        // Mute and fast-forward ad audio
                        var audios = document.querySelectorAll('audio');
                        for (var i = 0; i < audios.length; i++) {
                            var a = audios[i];
                            var src = a.src || '';
                            var isPreview = src.indexOf('mp3-preview') !== -1 || src.indexOf('p.scdn.co') !== -1;
                            if (isPreview) {
                                if (a.muted) a.muted = false;
                                continue;
                            }
                            if (isAd) {
                                a.muted = true;
                                if (a.duration && isFinite(a.duration) && a.duration > 0 && !a.paused) {
                                    a.currentTime = a.duration - 0.01;
                                }
                            } else {
                                if (a.muted) a.muted = false;
                            }
                        }

                        // 5. Shuffle Mismatch Corrector: If Spotify starts playing the WRONG shuffle song instead of the user's clicked track!
                        if (window.__hazeTargetTrack && (Date.now() - window.__hazeTargetTrack.time) < 10000 && window.__hazeTargetTrack.id) {
                            var tgt = window.__hazeTargetTrack.title.toLowerCase();
                            if (currentSongTitle && tgt && currentSongTitle !== tgt && currentSongTitle.indexOf(tgt) === -1 && tgt.indexOf(currentSongTitle) === -1 && !isAd) {
                                // Mismatch detected! Fetch target song preview directly and play it!
                                if (!window.__hazeCorrectingTrack || window.__hazeCorrectingTrack !== window.__hazeTargetTrack.id) {
                                    window.__hazeCorrectingTrack = window.__hazeTargetTrack.id;
                                    var embedUrl = 'https://open.spotify.com/embed/track/' + window.__hazeTargetTrack.id;
                                    fetch(embedUrl).then(function(res) { return res.text(); }).then(function(html) {
                                        var m = html.match(/"audioPreview":\s*\{\s*"url":\s*"([^"]+)"/);
                                        if (m && m[1]) {
                                            var previewAudioUrl = m[1].replace(/\\u002F/g, '/');
                                            var mainAudio = document.querySelector('audio') || new Audio();
                                            mainAudio.src = previewAudioUrl;
                                            mainAudio.muted = false;
                                            mainAudio.play().catch(function(_) {});
                                            // Update now-playing UI
                                            if (titleEl) titleEl.textContent = window.__hazeTargetTrack.title;
                                        }
                                    }).catch(function(_) {});
                                }
                            }
                        }

                        // Canvas Video Streaming
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
                            '[data-testid="upsell-modal"] { display: none !important; pointer-events: none !important; }',
                            'div.GenericModal__overlay { pointer-events: none !important; background: transparent !important; }'
                        ].join('\n');
                        (document.head || document.documentElement).appendChild(s);
                    }
                } catch(_) {}
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
                    meta.setAttribute('content', 'width=1280, initial-scale=0.35, minimum-scale=0.25, maximum-scale=5.0, user-scalable=yes');
                    if (navigator.userAgentData) {
                        try {
                            Object.defineProperty(navigator.userAgentData, 'mobile', { get: function() { return false; }, configurable: true });
                        } catch(_) {}
                    }
                } catch(e) {}
            })();
        """.trimIndent()

        val MOBILE_VIEWPORT_SCRIPT = """
            (function() {
                try {
                    var meta = document.querySelector('meta[name="viewport"]');
                    if (!meta) {
                        meta = document.createElement('meta');
                        meta.name = 'viewport';
                        (document.head || document.documentElement).appendChild(meta);
                    }
                    meta.setAttribute('content', 'width=device-width, initial-scale=1.0, minimum-scale=1.0, maximum-scale=5.0, user-scalable=yes');
                    if (navigator.userAgentData) {
                        try {
                            Object.defineProperty(navigator.userAgentData, 'mobile', { get: function() { return true; }, configurable: true });
                        } catch(_) {}
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

                // 2. DOM Section Collapser: Finds "Advertisement", "Sponsored", "Anuncio", or "Ad" badges and collapses wrapper sections!
                function collapseAdPlaceholdersAndSections() {
                    try {
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
