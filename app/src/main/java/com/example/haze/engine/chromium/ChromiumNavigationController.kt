package com.example.haze.engine.chromium

import android.webkit.WebView
import com.example.haze.engine.INavigationController
import com.example.haze.engine.NavigationEntry
import com.example.haze.engine.NavigationHistory

/**
 * Navigation controller implementation for Chromium-backed tabs.
 */
class ChromiumNavigationController(
    private val webView: WebView
) : INavigationController {

    override fun loadUrl(url: String, headers: Map<String, String>?) {
        if (headers.isNullOrEmpty()) {
            webView.loadUrl(url)
        } else {
            webView.loadUrl(url, headers)
        }
    }

    override fun loadDataWithBaseURL(
        baseUrl: String?,
        data: String,
        mimeType: String?,
        encoding: String?,
        historyUrl: String?
    ) {
        webView.loadDataWithBaseURL(baseUrl, data, mimeType ?: "text/html", encoding ?: "UTF-8", historyUrl)
    }

    override fun canGoBack(): Boolean = webView.canGoBack()

    override fun canGoForward(): Boolean = webView.canGoForward()

    override fun goBack() {
        if (webView.canGoBack()) {
            webView.goBack()
        }
    }

    override fun goForward() {
        if (webView.canGoForward()) {
            webView.goForward()
        }
    }

    override fun goBackOrForward(steps: Int) {
        if (webView.canGoBackOrForward(steps)) {
            webView.goBackOrForward(steps)
        }
    }

    override fun reload() {
        webView.reload()
    }

    override fun stopLoading() {
        webView.stopLoading()
    }

    override fun getNavigationHistory(): NavigationHistory {
        val list = webView.copyBackForwardList()
        val entries = mutableListOf<NavigationEntry>()
        val currentIndex = list.currentIndex
        for (i in 0 until list.size) {
            val item = list.getItemAtIndex(i)
            entries.add(
                NavigationEntry(
                    index = i,
                    url = item.url,
                    originalUrl = item.originalUrl,
                    title = item.title,
                    favicon = item.favicon
                )
            )
        }
        return NavigationHistory(currentIndex = currentIndex, entries = entries)
    }

    override fun clearHistory() {
        webView.clearHistory()
    }
}
