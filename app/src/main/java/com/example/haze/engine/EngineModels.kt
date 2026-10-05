package com.example.haze.engine

import android.graphics.Bitmap
import android.net.Uri
import android.view.View

/**
 * Global configuration options for the browser engine.
 */
data class EngineConfig(
    val enableJavaScript: Boolean = true,
    val enableDomStorage: Boolean = true,
    val enableDatabase: Boolean = true,
    val enableHardwareAcceleration: Boolean = true,
    val enableMultiProcess: Boolean = true,
    val customUserAgent: String? = null,
    val defaultEncoding: String = "UTF-8",
    val darkTheme: Boolean = false
)

/**
 * Security status of the loaded web page.
 */
sealed class PageSecurityState {
    object Secure : PageSecurityState()
    object InsecureHttp : PageSecurityState()
    data class MixedContent(val hasActiveContent: Boolean) : PageSecurityState()
    data class SslWarning(val message: String) : PageSecurityState()
    object Unknown : PageSecurityState()
}

/**
 * Structured details about an SSL / TLS error.
 */
data class SslErrorInfo(
    val primaryError: Int,
    val url: String,
    val certificateSummary: String,
    val issuedTo: String,
    val issuedBy: String,
    val validNotBefore: String,
    val validNotAfter: String
)

/**
 * Web permission request (Camera, Microphone, Geolocation, etc.).
 */
interface EnginePermissionRequest {
    val origin: Uri
    val resources: Array<String>
    fun grant(resources: Array<String>)
    fun deny()
}

/**
 * File chooser parameters for <input type="file">.
 */
data class EngineFileChooserParams(
    val mode: Int,
    val acceptTypes: Array<String>,
    val isCaptureEnabled: Boolean,
    val title: CharSequence?
) {
    companion object {
        const val MODE_OPEN = 0
        const val MODE_OPEN_MULTIPLE = 1
        const val MODE_SAVE = 3
    }
}

/**
 * Custom fullscreen view callback (e.g. HTML5 fullscreen video).
 */
fun interface EngineCustomViewCallback {
    fun onCustomViewHidden()
}

/**
 * Request interception response (for ad-blocking, privacy shielding, or custom caching).
 */
data class EngineInterceptResult(
    val mimeType: String,
    val encoding: String,
    val data: java.io.InputStream,
    val responseHeaders: Map<String, String> = emptyMap(),
    val statusCode: Int = 200,
    val reasonPhrase: String = "OK"
)

/**
 * Navigation entry in the tab session history.
 */
data class NavigationEntry(
    val index: Int,
    val url: String,
    val originalUrl: String,
    val title: String,
    val favicon: Bitmap? = null
)

/**
 * Complete navigation history representation of a tab.
 */
data class NavigationHistory(
    val currentIndex: Int,
    val entries: List<NavigationEntry>
)
