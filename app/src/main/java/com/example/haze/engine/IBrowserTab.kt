package com.example.haze.engine

import android.graphics.Bitmap
import android.view.View

/**
 * Interface representing a single browser tab / session.
 */
interface IBrowserTab {
    /**
     * Unique ID of the tab.
     */
    val id: Long

    /**
     * True if this tab operates in an isolated private/incognito profile.
     */
    val isPrivate: Boolean

    /**
     * The navigation controller for this tab.
     */
    val navigationController: INavigationController

    /**
     * The current page title.
     */
    var title: String

    /**
     * The current URL.
     */
    var url: String

    /**
     * The favicon bitmap of the currently loaded page.
     */
    var favicon: Bitmap?

    /**
     * True if the tab is configured to request desktop user agent and viewport.
     */
    var isDesktop: Boolean

    /**
     * Current loading progress (0 - 100).
     */
    val progress: Int

    /**
     * The Android View that renders the web content (to be embedded into the UI hierarchy).
     */
    val renderView: View

    /**
     * Registers an observer to receive page events, errors, and lifecycle updates.
     */
    fun setEngineObserver(observer: IEngineObserver)

    /**
     * Evaluates a JavaScript snippet within the tab context.
     */
    fun evaluateJavascript(script: String, callback: ((String?) -> Unit)? = null)

    /**
     * Searches for a text query inside the currently loaded page.
     */
    fun findInPage(query: String, forward: Boolean)

    /**
     * Asynchronously finds all occurrences of the query.
     */
    fun findAllAsync(find: String)

    /**
     * Highlights and moves to the next or previous found search match.
     */
    fun findNext(forward: Boolean)

    /**
     * Clears all search highlighting from the page.
     */
    fun clearMatches()

    /**
     * Zooms into the page content.
     */
    fun zoomIn(): Boolean

    /**
     * Zooms out of the page content.
     */
    fun zoomOut(): Boolean

    /**
     * Resets the zoom level to 100%.
     */
    fun resetZoom()

    /**
     * Clears cached resources for this tab.
     */
    fun clearCache(includeDiskFiles: Boolean = true)

    /**
     * Sets background color of the render surface.
     */
    fun setBackgroundColor(color: Int)

    /**
     * Dynamically enables or disables JavaScript execution for this tab.
     */
    fun setJavaScriptEnabled(enabled: Boolean)

    /**
     * Creates a print document adapter for Android printing services.
     */
    fun createPrintDocumentAdapter(documentName: String): Any?

    /**
     * Captures a snapshot bitmap of the visible viewport.
     */
    fun captureThumbnail(callback: (Bitmap?) -> Unit)

    /**
     * Suspends timers, animations, and background work for this tab.
     */
    fun onPause()

    /**
     * Resumes execution for this tab when brought into foreground.
     */
    fun onResume()

    /**
     * Destroys the tab session and releases all native and memory resources.
     */
    fun destroy()

    /**
     * Reloads the current page.
     */
    fun reload() {
        navigationController.reload()
    }

    /**
     * Stops loading the current page.
     */
    fun stopLoading() {
        navigationController.stopLoading()
    }

    fun canGoBack(): Boolean = navigationController.canGoBack()
    fun canGoForward(): Boolean = navigationController.canGoForward()
    fun goBack() = navigationController.goBack()
    fun goForward() = navigationController.goForward()
    fun loadUrl(targetUrl: String, headers: Map<String, String>? = null) = navigationController.loadUrl(targetUrl, headers)
    fun loadDataWithBaseURL(baseUrl: String?, data: String, mimeType: String?, encoding: String?, historyUrl: String?) =
        navigationController.loadDataWithBaseURL(baseUrl, data, mimeType, encoding, historyUrl)
}
