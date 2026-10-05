package com.example.haze.engine

/**
 * Interface controlling session navigation for an individual tab.
 */
interface INavigationController {
    /**
     * Navigates to a given URL with optional HTTP request headers.
     */
    fun loadUrl(url: String, headers: Map<String, String>? = null)

    /**
     * Loads custom HTML data into the tab with an optional base URL.
     */
    fun loadDataWithBaseURL(
        baseUrl: String?,
        data: String,
        mimeType: String?,
        encoding: String?,
        historyUrl: String?
    )

    /**
     * Checks if the tab has a previous page in its back stack.
     */
    fun canGoBack(): Boolean

    /**
     * Checks if the tab has a forward page in its forward stack.
     */
    fun canGoForward(): Boolean

    /**
     * Navigates back in the tab session history.
     */
    fun goBack()

    /**
     * Navigates forward in the tab session history.
     */
    fun goForward()

    /**
     * Navigates to a specific offset in session history (e.g. -2 or +1).
     */
    fun goBackOrForward(steps: Int)

    /**
     * Reloads the current page.
     */
    fun reload()

    /**
     * Stops loading the current page.
     */
    fun stopLoading()

    /**
     * Retrieves the complete session history for the tab.
     */
    fun getNavigationHistory(): NavigationHistory

    /**
     * Clears the tab's internal back/forward navigation stack.
     */
    fun clearHistory()
}
