package com.example.haze.engine.nativechromium

import android.content.Context
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.widget.FrameLayout
import com.example.haze.engine.IBrowserTab
import com.example.haze.engine.IEngineObserver
import com.example.haze.engine.INavigationController
import com.example.haze.engine.SslErrorInfo
import org.chromium.content_public.browser.JavaScriptCallback
import org.chromium.content_public.browser.JavascriptInjector
import org.chromium.content_public.browser.WebContents
import org.chromium.content_public.browser.WebContentsObserver
import org.chromium.url.GURL
import org.json.JSONObject
import java.util.UUID

class NativeChromiumTab(
    private val context: Context,
    override val isPrivate: Boolean
) : IBrowserTab {

    override val id: Long = UUID.randomUUID().mostSignificantBits and Long.MAX_VALUE

    val shellManager: org.chromium.content_shell.ShellManager =
        NativeChromiumBrowserEngine.globalShellManager ?: org.chromium.content_shell.ShellManager(context, null).also { sm ->
            val activity = org.chromium.base.ContextUtils.activityFromContext(context) ?: (context as? android.app.Activity)
            val win = if (activity != null) {
                org.chromium.ui.base.ActivityWindowAndroid(activity, false, org.chromium.ui.base.IntentRequestTracker.createFromActivity(activity), null, true)
            } else {
                org.chromium.ui.base.WindowAndroid(context, false)
            }
            sm.setWindow(win)
            NativeChromiumBrowserEngine.globalShellManager = sm
            NativeChromiumBrowserEngine.globalWindowAndroid = win
        }

    private val tabContainer: FrameLayout = FrameLayout(context)

    override val navigationController: INavigationController = NativeNavigationController(this)

    override var title: String = ""
    override var url: String = ""
    override var favicon: Bitmap? = null
    override var isDesktop: Boolean = false
        set(value) {
            field = value
            try {
                getWebContents()?.navigationController?.setUseDesktopUserAgent(value, true, false)
            } catch (_: Throwable) {}
        }
    override var progress: Int = 0

    override val renderView: View
        get() {
            attachShellToThisTab()
            return tabContainer
        }

    internal var observer: IEngineObserver? = null
    private var webContentsObserver: WebContentsObserver? = null
    private var observedWebContents: WebContents? = null
    private val jsInterfaces = mutableMapOf<String, Any>()
    private var lastFindQuery: String = ""
    private var currentZoomLevel: Float = 1.0f

    init {
        if (shellManager.activeShell == null) {
            shellManager.launchShell("about:blank")
        }
        attachShellToThisTab()
    }

    fun attachShellToThisTab() {
        NativeChromiumBrowserEngine.activeTab = this
        if (shellManager.parent !== tabContainer) {
            (shellManager.parent as? ViewGroup)?.removeView(shellManager)
            tabContainer.addView(
                shellManager,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
        }
        hideShellDebugToolbar()
        ensureObserversAndInterfaces()
    }

    fun hideShellDebugToolbar() {
        try {
            shellManager.activeShell?.findViewById<View>(com.example.haze.R.id.toolbar)?.visibility = View.GONE
        } catch (_: Throwable) {}
    }

    fun getWebContents(): WebContents? {
        val wc = shellManager.activeShell?.webContents
        if (wc != null) {
            ensureObserversAndInterfaces(wc)
        }
        return wc
    }

    fun ensureObserversAndInterfaces(wc: WebContents? = shellManager.activeShell?.webContents) {
        if (wc == null) return
        hideShellDebugToolbar()
        try {
            val injector = JavascriptInjector.fromWebContents(wc)
            injector?.setAllowInspection(true)
            for ((name, obj) in jsInterfaces) {
                injector?.addPossiblyUnsafeInterface(obj, name, JavascriptInterface::class.java)
            }
        } catch (_: Throwable) {}

        if (observedWebContents !== wc || webContentsObserver == null) {
            observedWebContents = wc
            attachWebContentsObserver(wc)
        }
    }

    private fun normalizeEngineUrl(rawSpec: String?): String {
        val spec = rawSpec ?: ""
        if (spec.startsWith("data:") || spec.startsWith("https://haze.local")) {
            return "haze://home"
        }
        return spec
    }

    private fun attachWebContentsObserver(wc: WebContents) {
        try {
            webContentsObserver = object : WebContentsObserver(wc) {
                override fun didStartLoading(gurl: GURL?) {
                    val targetTab = NativeChromiumBrowserEngine.activeTab ?: this@NativeChromiumTab
                    targetTab.hideShellDebugToolbar()
                    val normalized = normalizeEngineUrl(gurl?.spec ?: targetTab.url)
                    if (normalized.isNotEmpty()) {
                        targetTab.url = normalized
                        targetTab.observer?.onPageStarted(targetTab, normalized, targetTab.favicon)
                    }
                }

                override fun didStopLoading(gurl: GURL?, isKnownValid: Boolean) {
                    val targetTab = NativeChromiumBrowserEngine.activeTab ?: this@NativeChromiumTab
                    targetTab.hideShellDebugToolbar()
                    val raw = gurl?.spec?.takeIf { it.isNotEmpty() } ?: wc.visibleUrl?.spec ?: targetTab.url
                    val normalized = normalizeEngineUrl(raw)
                    targetTab.url = normalized
                    targetTab.observer?.onPageFinished(targetTab, normalized)
                }

                override fun loadProgressChanged(loadProgress: Float) {
                    val targetTab = NativeChromiumBrowserEngine.activeTab ?: this@NativeChromiumTab
                    val p = (loadProgress * 100).toInt().coerceIn(0, 100)
                    targetTab.progress = p
                    targetTab.observer?.onPageProgress(targetTab, p)
                }

                override fun titleWasSet(newTitle: String?) {
                    val targetTab = NativeChromiumBrowserEngine.activeTab ?: this@NativeChromiumTab
                    if (!newTitle.isNullOrEmpty() && targetTab.url != "haze://home") {
                        targetTab.title = newTitle
                        targetTab.observer?.onTitleReceived(targetTab, newTitle)
                    }
                }

                override fun didFailLoad(isInPrimaryMainFrame: Boolean, errorCode: Int, failingGurl: GURL?, rfhLifecycleState: Int) {
                    if (isInPrimaryMainFrame && errorCode != -3) { // Ignore ERR_ABORTED (-3)
                        val targetTab = NativeChromiumBrowserEngine.activeTab ?: this@NativeChromiumTab
                        val failUrl = normalizeEngineUrl(failingGurl?.spec ?: targetTab.url)
                        if (failUrl != "haze://home" && !failUrl.startsWith("about:")) {
                            targetTab.observer?.onReceivedError(
                                targetTab,
                                errorCode,
                                "Load failed with code $errorCode",
                                failUrl
                            )
                        }
                    }
                }
            }
        } catch (_: Throwable) {}
    }

    override fun setEngineObserver(observer: IEngineObserver) {
        this.observer = observer
        ensureObserversAndInterfaces()
    }

    override fun evaluateJavascript(script: String, callback: ((String?) -> Unit)?) {
        val wc = getWebContents()
        if (wc != null && !wc.isDestroyed) {
            try {
                wc.evaluateJavaScriptForTests(script, object : JavaScriptCallback {
                    override fun handleJavaScriptResult(jsonResult: String?) {
                        callback?.invoke(jsonResult)
                    }
                })
                return
            } catch (_: Throwable) {}
        }
        callback?.invoke(null)
    }

    override fun findInPage(query: String, forward: Boolean) {
        lastFindQuery = query
        if (query.isEmpty()) {
            clearMatches()
            return
        }
        val escaped = JSONObject.quote(query)
        evaluateJavascript("window.find($escaped, false, ${!forward}, true);", null)
    }

    override fun findAllAsync(query: String) {
        lastFindQuery = query
        if (query.isEmpty()) {
            clearMatches()
            return
        }
        val escaped = JSONObject.quote(query)
        evaluateJavascript("if (window.getSelection) window.getSelection().removeAllRanges(); window.find($escaped, false, false, true);", null)
    }

    override fun findNext(forward: Boolean) {
        if (lastFindQuery.isNotEmpty()) {
            val escaped = JSONObject.quote(lastFindQuery)
            evaluateJavascript("window.find($escaped, false, ${!forward}, true);", null)
        }
    }

    override fun clearMatches() {
        evaluateJavascript("if (window.getSelection) window.getSelection().removeAllRanges();", null)
    }

    override fun addJavascriptInterface(obj: Any, name: String) {
        jsInterfaces[name] = obj
        ensureObserversAndInterfaces()
    }

    override fun removeJavascriptInterface(name: String) {
        jsInterfaces.remove(name)
        val wc = shellManager.activeShell?.webContents ?: return
        try {
            JavascriptInjector.fromWebContents(wc)?.removeInterface(name)
        } catch (_: Throwable) {}
    }

    override fun setBackgroundColor(color: Int) {
        tabContainer.setBackgroundColor(color)
        shellManager.setBackgroundColor(color)
    }

    override fun zoomIn(): Boolean {
        currentZoomLevel = (currentZoomLevel + 0.15f).coerceAtMost(3.0f)
        evaluateJavascript("document.documentElement.style.zoom = '$currentZoomLevel';", null)
        return true
    }

    override fun zoomOut(): Boolean {
        currentZoomLevel = (currentZoomLevel - 0.15f).coerceAtLeast(0.5f)
        evaluateJavascript("document.documentElement.style.zoom = '$currentZoomLevel';", null)
        return true
    }

    override fun resetZoom() {
        currentZoomLevel = 1.0f
        evaluateJavascript("document.documentElement.style.zoom = '1.0';", null)
    }

    override fun captureThumbnail(callback: (Bitmap?) -> Unit) {
        try {
            val view = tabContainer.takeIf { it.width > 0 && it.height > 0 } ?: shellManager
            if (view.width > 0 && view.height > 0) {
                val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                val canvas = android.graphics.Canvas(bitmap)
                view.draw(canvas)
                callback(bitmap)
                return
            }
        } catch (_: Throwable) {}
        callback(null)
    }

    override fun onPause() {}

    override fun onResume() {
        attachShellToThisTab()
    }

    override fun destroy() {
        try {
            if (shellManager.parent === tabContainer) {
                tabContainer.removeView(shellManager)
            }
            if (NativeChromiumBrowserEngine.activeTab === this) {
                NativeChromiumBrowserEngine.activeTab = null
            }
        } catch (_: Throwable) {}
    }

    override fun setJavaScriptEnabled(enabled: Boolean) {}

    override fun getCertificateInfo(): SslErrorInfo? = null

    override fun clearCache(includeDiskFiles: Boolean) {}

    override fun createPrintDocumentAdapter(documentName: String): Any? = null

    override fun reload() {
        navigationController.reload()
    }

    override fun stopLoading() {
        navigationController.stopLoading()
    }
}
