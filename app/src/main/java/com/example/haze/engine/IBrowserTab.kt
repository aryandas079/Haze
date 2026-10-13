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
     * Finds all instances of query string asynchronously.
     */
    fun findAllAsync(query: String)

    /**
     * Highlights and moves to the next or previous search match.
     */
    fun findNext(forward: Boolean)

    /**
     * Clears all search highlighting from the page.
     */
    fun clearMatches()

    /**
     * Injects an object as a JavaScript global bridge.
     */
    fun addJavascriptInterface(obj: Any, name: String)

    /**
     * Removes an injected JavaScript bridge.
     */
    fun removeJavascriptInterface(name: String)

    /**
     * Sets the tab background color.
     */
    fun setBackgroundColor(color: Int)

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
     * Toggles JavaScript execution for this tab.
     */
    fun setJavaScriptEnabled(enabled: Boolean)

    /**
     * Retrieves SSL/TLS certificate details for the currently loaded page.
     */
    fun getCertificateInfo(): SslErrorInfo?

    /**
     * Clears cache for this individual tab.
     */
    fun clearCache(includeDiskFiles: Boolean)

    /**
     * Creates an Android PrintDocumentAdapter for the page.
     */
    fun createPrintDocumentAdapter(documentName: String): Any?

    /**
     * Reloads the current page.
     */
    fun reload()

    /**
     * Stops loading the current page.
     */
    fun stopLoading()

    /**
     * Sets a listener to be notified of find-in-page progress and match counts.
     */
    fun setFindListener(listener: ((activeMatchOrdinal: Int, numberOfMatches: Int, isDoneCounting: Boolean) -> Unit)?)

    /**
     * Saves the current view as a web archive.
     */
    fun saveWebArchive(filename: String, autoname: Boolean, callback: ((String?) -> Unit)? = null)
}
