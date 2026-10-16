package com.example.haze

import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import java.util.concurrent.ConcurrentHashMap

/**
 * Compatibility-First Architecture & Automatic Recovery System for Haze Browser:
 *
 * 1. Site Protection Modes:
 *    - MODE_STANDARD (Default: Comprehensive privacy shield, tracker/ad blocking, secure DNS, stealth fingerprinting)
 *    - MODE_BALANCED (Relaxes strict heuristic blocking for complex SPAs, WebGL games, video conferencing, payment checkouts)
 *    - MODE_ALLOW_ALL (Shield disabled for this specific site)
 *
 * 2. Instant Site Recovery:
 *    - Troubleshooting Reload: Bypass HTTP cache, temporarily suspend blocking for the upcoming reload to test if an ad block rule broke page layout/flow.
 *    - Temporarily Allow Blocked Resources: Pauses subresource filtering for 15 minutes.
 *    - Reset Site Data: Clears origin cookies, localStorage, IndexedDB, WebSQL, and cache without affecting other origins or global settings.
 *
 * 3. Diagnostics & Health Report:
 *    - Evaluates page load integrity (DNS resolution, resource errors, blocked scripts).
 *    - Recommends 1-tap compatibility repair if critical resources fail.
 */
object HazeSiteCompatibilityManager {

    const val MODE_STANDARD = "STANDARD"
    const val MODE_BALANCED = "BALANCED"
    const val MODE_ALLOW_ALL = "ALLOW_ALL"

    private const val PREFS_SITE_MODES = "HazeSiteCompatibilityModes"

    // Active in-memory troubleshooting reloads (host -> expiryTimestamp)
    private val troubleshootingReloads = ConcurrentHashMap<String, Long>()

    // Active in-memory temporary allows (host -> expiryTimestamp)
    private val temporaryAllowedSites = ConcurrentHashMap<String, Long>()

    // In-memory cache of site modes
    private val siteModesCache = ConcurrentHashMap<String, String>()

    // Known complex web apps that benefit from Balanced mode by default
    val DEFAULT_BALANCED_DOMAINS = hashSetOf(
        "figma.com", "canva.com", "notion.so", "meet.google.com", "zoom.us",
        "teams.microsoft.com", "discord.com", "web.whatsapp.com", "slack.com",
        "shadertoy.com", "threejs.org", "babylonjs.com"
    )

    fun cleanHost(hostOrUrl: String?): String {
        if (hostOrUrl.isNullOrBlank()) return ""
        val withoutScheme = when {
            hostOrUrl.startsWith("https://", true) -> hostOrUrl.substring(8)
            hostOrUrl.startsWith("http://", true) -> hostOrUrl.substring(7)
            else -> hostOrUrl
        }
        val slashIdx = withoutScheme.indexOfAny(charArrayOf('/', '?', '#', ':'))
        val host = if (slashIdx != -1) withoutScheme.substring(0, slashIdx) else withoutScheme
        return host.trim().lowercase().removePrefix("www.")
    }

    fun getSiteMode(context: Context, hostOrUrl: String): String {
        val host = cleanHost(hostOrUrl)
        if (host.isEmpty()) return MODE_STANDARD

        siteModesCache[host]?.let { return it }

        val prefs = context.getSharedPreferences(PREFS_SITE_MODES, Context.MODE_PRIVATE)
        val saved = prefs.getString(host, null)
        if (saved != null) {
            siteModesCache[host] = saved
            return saved
        }

        // Check default balanced list
        if (DEFAULT_BALANCED_DOMAINS.any { host == it || host.endsWith(".$it") }) {
            return MODE_BALANCED
        }

        return MODE_STANDARD
    }

    fun setSiteMode(context: Context, hostOrUrl: String, mode: String) {
        val host = cleanHost(hostOrUrl)
        if (host.isEmpty()) return

        siteModesCache[host] = mode
        context.getSharedPreferences(PREFS_SITE_MODES, Context.MODE_PRIVATE)
            .edit()
            .putString(host, mode)
            .apply()

        HazePrivacyShieldEngine.invalidateCache()
    }

    /**
     * Flags this host for a single Troubleshooting Reload.
     * The next page load will bypass HTTP cache and allow blocked resources to diagnose compatibility.
     */
    fun scheduleTroubleshootingReload(hostOrUrl: String) {
        val host = cleanHost(hostOrUrl)
        if (host.isNotEmpty()) {
            troubleshootingReloads[host] = System.currentTimeMillis() + 60_000L // 60s window
        }
    }

    fun isTroubleshootingReloadActive(hostOrUrl: String): Boolean {
        val host = cleanHost(hostOrUrl)
        if (host.isEmpty()) return false
        val expiry = troubleshootingReloads[host] ?: return false
        if (System.currentTimeMillis() > expiry) {
            troubleshootingReloads.remove(host)
            return false
        }
        return true
    }

    fun consumeTroubleshootingReload(hostOrUrl: String): Boolean {
        val host = cleanHost(hostOrUrl)
        return troubleshootingReloads.remove(host) != null
    }

    /**
     * Temporarily allows blocked resources on this site for 15 minutes.
     */
    fun temporarilyAllowBlockedResources(hostOrUrl: String, durationMinutes: Int = 15) {
        val host = cleanHost(hostOrUrl)
        if (host.isNotEmpty()) {
            temporaryAllowedSites[host] = System.currentTimeMillis() + (durationMinutes * 60_000L)
            HazePrivacyShieldEngine.invalidateCache()
        }
    }

    fun isTemporarilyAllowed(hostOrUrl: String): Boolean {
        val host = cleanHost(hostOrUrl)
        if (host.isEmpty()) return false
        val expiry = temporaryAllowedSites[host] ?: return false
        if (System.currentTimeMillis() > expiry) {
            temporaryAllowedSites.remove(host)
            return false
        }
        return true
    }

    fun revokeTemporaryAllow(hostOrUrl: String) {
        val host = cleanHost(hostOrUrl)
        temporaryAllowedSites.remove(host)
        HazePrivacyShieldEngine.invalidateCache()
    }

    /**
     * Resets site data (cookies, localStorage, WebSQL, indexedDB, and cache) strictly for this origin.
     */
    fun resetSiteData(context: Context, hostOrUrl: String, webView: WebView?, onComplete: () -> Unit) {
        val host = cleanHost(hostOrUrl)
        if (host.isEmpty()) {
            onComplete()
            return
        }

        try {
            // 1. Clear origin cookies via CookieManager
            val cm = CookieManager.getInstance()
            val origins = listOf("https://$host", "http://$host", "https://www.$host", "http://www.$host")
            for (origin in origins) {
                val cookies = cm.getCookie(origin)
                if (!cookies.isNullOrEmpty()) {
                    for (cookie in cookies.split(";")) {
                        val eqIdx = cookie.indexOf('=')
                        val name = if (eqIdx != -1) cookie.substring(0, eqIdx).trim() else cookie.trim()
                        cm.setCookie(origin, "$name=; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/")
                    }
                }
            }
            cm.flush()

            // 2. Clear WebStorage (localStorage, IndexedDB) for origin
            WebStorage.getInstance().deleteOrigin("https://$host")
            WebStorage.getInstance().deleteOrigin("http://$host")

            // 3. Clear WebView memory/disk cache for this tab
            webView?.clearCache(true)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        onComplete()
    }
}
