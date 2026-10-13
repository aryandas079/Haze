package com.example.haze.engine.nativechromium

import com.example.haze.engine.INavigationController
import com.example.haze.engine.NavigationHistory
import org.chromium.content_public.browser.LoadUrlParams

class NativeNavigationController(private val tab: NativeChromiumTab) : INavigationController {

    override fun loadUrl(url: String, headers: Map<String, String>?) {
        tab.url = url
        tab.attachShellToThisTab()
        val webContents = tab.getWebContents()
        if (webContents != null) {
            tab.ensureObserversAndInterfaces(webContents)
            val params = LoadUrlParams(url)
            if (headers != null) {
                params.setExtraHeaders(headers)
            }
            webContents.navigationController.loadUrl(params)
        } else {
            tab.shellManager.launchShell(url)
            tab.ensureObserversAndInterfaces()
        }
    }

    override fun loadDataWithBaseURL(baseUrl: String?, data: String, mimeType: String?, encoding: String?, historyUrl: String?) {
        tab.url = baseUrl ?: "haze://home"
        tab.attachShellToThisTab()
        val webContents = tab.getWebContents()
        if (webContents != null) {
            tab.ensureObserversAndInterfaces(webContents)
            val mime = mimeType ?: "text/html"
            val charset = encoding ?: "utf-8"
            val base64Data = android.util.Base64.encodeToString(
                data.toByteArray(Charsets.UTF_8),
                android.util.Base64.NO_WRAP
            )
            val safeBaseUrl = if (baseUrl.isNullOrEmpty() || baseUrl.startsWith("haze://")) {
                "https://haze.local/"
            } else {
                baseUrl
            }
            val safeHistoryUrl = if (historyUrl.isNullOrEmpty() || historyUrl.startsWith("haze://")) {
                safeBaseUrl
            } else {
                historyUrl
            }
            val params = LoadUrlParams.createLoadDataParamsWithBaseUrl(
                base64Data,
                mime,
                true,
                safeBaseUrl,
                safeHistoryUrl,
                charset
            )
            webContents.navigationController.loadUrl(params)
        } else {
            tab.shellManager.launchShell("about:blank")
            tab.ensureObserversAndInterfaces()
        }
    }

    override fun goBackOrForward(steps: Int) {
        val nc = tab.getWebContents()?.navigationController ?: return
        if (steps < 0) {
            for (i in 0 until (-steps)) {
                if (nc.canGoBack()) nc.goBack()
            }
        } else if (steps > 0) {
            for (i in 0 until steps) {
                if (nc.canGoForward()) nc.goForward()
            }
        }
    }

    override fun reload() {
        tab.getWebContents()?.navigationController?.reload(true)
    }

    override fun stopLoading() {
        tab.getWebContents()?.stop()
    }

    override fun getNavigationHistory(): NavigationHistory {
        return NavigationHistory(-1, emptyList())
    }

    override fun clearHistory() {
        tab.getWebContents()?.navigationController?.clearHistory()
    }

    override fun canGoBack(): Boolean {
        return tab.getWebContents()?.navigationController?.canGoBack() ?: false
    }

    override fun goBack() {
        tab.getWebContents()?.navigationController?.goBack()
    }

    override fun canGoForward(): Boolean {
        return tab.getWebContents()?.navigationController?.canGoForward() ?: false
    }

    override fun goForward() {
        tab.getWebContents()?.navigationController?.goForward()
    }
}
