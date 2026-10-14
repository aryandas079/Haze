package com.example.haze

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri

/**
 * Manages per-site hardware and privacy permissions:
 * - Camera (Denied, Allowed, Ask)
 * - Microphone (Denied, Allowed, Ask)
 * - Location (Denied, Allowed, Ask)
 * - Notifications (Denied, Allowed, Ask)
 *
 * Persists user choices per hostname.
 */
object HazeSitePermissionManager {

    enum class State(val label: String) {
        ASK("Ask"),
        ALLOWED("Allowed"),
        DENIED("Denied")
    }

    enum class PermissionType(val key: String, val displayName: String, val defaultState: State) {
        CAMERA("camera", "Camera", State.DENIED),
        MICROPHONE("mic", "Microphone", State.DENIED),
        LOCATION("location", "Location", State.ASK),
        NOTIFICATIONS("notifications", "Notifications", State.DENIED)
    }

    private const val PREFS_NAME = "HazeSitePermissions"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun cleanHost(hostOrUrl: String): String {
        if (hostOrUrl.isBlank()) return ""
        return runCatching {
            val uri = if (hostOrUrl.contains("://")) Uri.parse(hostOrUrl) else Uri.parse("https://$hostOrUrl")
            uri.host?.lowercase()?.removePrefix("www.")
        }.getOrNull()?.takeIf { it.isNotEmpty() } ?: hostOrUrl.lowercase().removePrefix("www.").trim()
    }

    fun getPermission(context: Context, hostOrUrl: String, type: PermissionType): State {
        val host = cleanHost(hostOrUrl)
        if (host.isEmpty()) return type.defaultState
        val raw = getPrefs(context).getString("${host}_${type.key}", null) ?: return type.defaultState
        return runCatching { State.valueOf(raw) }.getOrDefault(type.defaultState)
    }

    fun setPermission(context: Context, hostOrUrl: String, type: PermissionType, state: State) {
        val host = cleanHost(hostOrUrl)
        if (host.isEmpty()) return
        getPrefs(context).edit().putString("${host}_${type.key}", state.name).apply()
    }

    fun cyclePermission(context: Context, hostOrUrl: String, type: PermissionType): State {
        val current = getPermission(context, hostOrUrl, type)
        val next = when (current) {
            State.DENIED -> State.ASK
            State.ASK -> State.ALLOWED
            State.ALLOWED -> State.DENIED
        }
        setPermission(context, hostOrUrl, type, next)
        return next
    }

    fun getAllPermissions(context: Context, hostOrUrl: String): Map<PermissionType, State> {
        val host = cleanHost(hostOrUrl)
        return PermissionType.values().associateWith { type ->
            getPermission(context, host, type)
        }
    }

    fun resetPermissionsForHost(context: Context, hostOrUrl: String) {
        val host = cleanHost(hostOrUrl)
        if (host.isEmpty()) return
        val editor = getPrefs(context).edit()
        PermissionType.values().forEach { type ->
            editor.remove("${host}_${type.key}")
        }
        editor.apply()
    }
}
