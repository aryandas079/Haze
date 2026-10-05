package com.example.haze.engine

import android.graphics.Bitmap
import android.net.Uri
import android.view.View

/**
 * Observer interface that receives events, callbacks, and lifecycle hooks from an engine tab.
 */
interface IEngineObserver {
    fun onPageStarted(tab: IBrowserTab, url: String, favicon: Bitmap?) {}
    fun onPageFinished(tab: IBrowserTab, url: String) {}
    fun onPageProgress(tab: IBrowserTab, progress: Int) {}
    fun onTitleReceived(tab: IBrowserTab, title: String) {}
    fun onFaviconReceived(tab: IBrowserTab, favicon: Bitmap) {}
    fun onSecurityStateChanged(tab: IBrowserTab, state: PageSecurityState) {}

    /**
     * Called when a network or page load error occurs.
     */
    fun onReceivedError(tab: IBrowserTab, errorCode: Int, description: String, failingUrl: String) {}

    /**
     * Called when an SSL/TLS certificate verification error occurs.
     */
    fun onSslError(tab: IBrowserTab, sslError: SslErrorInfo, proceed: () -> Unit, cancel: () -> Unit) {
        cancel()
    }

    /**
     * Called when the underlying Chromium renderer process terminated or crashed.
     * @param crashed true if terminated abnormally (SIGSEGV, OOM, renderer crash), false if killed by OS to reclaim memory.
     */
    fun onRenderProcessGone(tab: IBrowserTab, crashed: Boolean): Boolean = false

    /**
     * Called when a website requests device permissions (Camera, Microphone, etc.).
     */
    fun onPermissionRequest(request: EnginePermissionRequest) {
        request.deny()
    }

    /**
     * Called when a website triggers an <input type="file"> file chooser dialog.
     */
    fun onShowFileChooser(fileParams: EngineFileChooserParams, callback: (Array<Uri>?) -> Unit): Boolean = false

    /**
     * Called when media/video requests to enter fullscreen.
     */
    fun onShowCustomView(view: View, callback: EngineCustomViewCallback) {}

    /**
     * Called when fullscreen custom view is dismissed.
     */
    fun onHideCustomView() {}

    /**
     * Called when a page tries to open a new tab/window via window.open() or target="_blank".
     */
    fun onNewWindowRequested(url: String, isUserGesture: Boolean, isDialog: Boolean): Boolean = false

    /**
     * Called when a download is requested by a website.
     */
    fun onDownloadRequested(
        url: String,
        userAgent: String,
        contentDisposition: String,
        mimeType: String,
        contentLength: Long
    ) {}

    /**
     * Called when media playback starts or pauses inside the tab.
     */
    fun onMediaStateChanged(tab: IBrowserTab, isPlaying: Boolean, width: Int, height: Int) {}

    /**
     * Gives the browser a chance to inspect, filter, or intercept a network request.
     */
    fun shouldInterceptRequest(url: String, isMainFrame: Boolean): EngineInterceptResult? = null

    /**
     * Gives the browser a chance to handle special intent/custom scheme navigations.
     */
    fun shouldOverrideUrlLoading(url: String): Boolean = false
}
