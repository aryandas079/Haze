package com.example.haze.engine.chromium

import android.content.Context
import android.os.Build
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewDatabase
import com.example.haze.engine.EngineConfig
import com.example.haze.engine.IBrowserEngine
import com.example.haze.engine.IBrowserTab

/**
 * Chromium browser engine controller managing global engine runtime, cookies, and tab creation.
 */
class ChromiumBrowserEngine private constructor() : IBrowserEngine {

    companion object {
        @Volatile
        private var instance: ChromiumBrowserEngine? = null

        fun getInstance(): ChromiumBrowserEngine {
            return instance ?: synchronized(this) {
                instance ?: ChromiumBrowserEngine().also { instance = it }
            }
        }
    }

    private var isInitialized = false
    private var engineConfig = EngineConfig()
    private var applicationContext: Context? = null

    override fun init(context: Context, config: EngineConfig) {
        if (isInitialized) return
        this.applicationContext = context.applicationContext
        this.engineConfig = config

        try {
            // Enable third-party cookie controls
            val cookieManager = CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)

            // Enable Chrome DevTools Protocol (CDP) remote debugging
            WebView.setWebContentsDebuggingEnabled(true)

            isInitialized = true
        } catch (e: Exception) {
            isInitialized = true
        }
    }

    override fun createTab(context: Context, isPrivate: Boolean): IBrowserTab {
        if (applicationContext == null) applicationContext = context.applicationContext
        return ChromiumBrowserTab(
            context = context,
            isPrivate = isPrivate,
            config = engineConfig
        )
    }

    override fun clearData(
        clearHistory: Boolean,
        clearCookies: Boolean,
        clearCache: Boolean,
        clearStorage: Boolean,
        onComplete: (() -> Unit)?
    ) {
        Thread {
            try {
                if (clearCookies) {
                    val cookieManager = CookieManager.getInstance()
                    cookieManager.removeAllCookies {
                        cookieManager.flush()
                    }
                }
                if (clearStorage) {
                    WebStorage.getInstance().deleteAllData()
                }
            } catch (_: Exception) {}
            onComplete?.invoke()
        }.start()
    }

    override fun clearPrivateSession(onComplete: (() -> Unit)?) {
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        handler.post {
            try {
                if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.MULTI_PROFILE)) {
                    val store = androidx.webkit.ProfileStore.getInstance()
                    store.deleteProfile("incognito")
                }
            } catch (_: Exception) {}
            
            Thread {
                try {
                    val cookieManager = CookieManager.getInstance()
                    cookieManager.removeSessionCookies {
                        cookieManager.flush()
                    }
                } catch (_: Exception) {}
                onComplete?.invoke()
            }.start()
        }
    }

    override fun clearFormData(context: Context) {
        try {
            val webDb = WebViewDatabase.getInstance(context.applicationContext)
            webDb.clearFormData()
            webDb.clearHttpAuthUsernamePassword()
        } catch (_: Exception) {}
    }

    override fun getEngineName(): String = "Chromium Engine"

    override fun getEngineVersion(): String {
        return try {
            val ctx = applicationContext
            val fullUa = if (ctx != null) WebSettings.getDefaultUserAgent(ctx) else ""
            val match = Regex("Chrome/([0-9.]+)").find(fullUa)
            match?.groupValues?.getOrNull(1) ?: "Chromium 124"
        } catch (_: Exception) {
            "Chromium 124"
        }
    }

    override fun isChromiumNative(): Boolean = true

    override fun shutdown() {
        try {
            CookieManager.getInstance().flush()
        } catch (_: Exception) {}
    }
}
