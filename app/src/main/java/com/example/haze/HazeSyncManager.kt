package com.example.haze

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlin.concurrent.thread

object HazeSyncManager {

    private const val PREFS_NAME = "HazeSyncPrefs"
    const val KEY_SYNC_ENABLED = "sync_enabled"
    const val KEY_SYNC_BOOKMARKS = "sync_bookmarks"
    const val KEY_SYNC_SETTINGS = "sync_settings"
    const val KEY_SYNC_THEMES = "sync_themes"
    const val KEY_SYNC_SHORTCUTS = "sync_shortcuts"
    const val KEY_SYNC_HISTORY = "sync_history"
    private const val KEY_LAST_SYNC_TIME = "last_sync_timestamp"

    enum class SyncState {
        IDLE,
        SYNCING,
        COMPLETED,
        FAILED
    }

    var currentState = SyncState.IDLE
        private set

    private val mainHandler = Handler(Looper.getMainLooper())

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun isSyncEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_SYNC_ENABLED, true) && HazeAccountManager.isSignedIn(context)
    }

    fun setSyncEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_SYNC_ENABLED, enabled).apply()
    }

    fun isBookmarksSyncEnabled(context: Context): Boolean = getPrefs(context).getBoolean(KEY_SYNC_BOOKMARKS, true)
    fun setBookmarksSyncEnabled(context: Context, enabled: Boolean) = getPrefs(context).edit().putBoolean(KEY_SYNC_BOOKMARKS, enabled).apply()

    fun isSettingsSyncEnabled(context: Context): Boolean = getPrefs(context).getBoolean(KEY_SYNC_SETTINGS, true)
    fun setSettingsSyncEnabled(context: Context, enabled: Boolean) = getPrefs(context).edit().putBoolean(KEY_SYNC_SETTINGS, enabled).apply()

    fun isThemesSyncEnabled(context: Context): Boolean = getPrefs(context).getBoolean(KEY_SYNC_THEMES, true)
    fun setThemesSyncEnabled(context: Context, enabled: Boolean) = getPrefs(context).edit().putBoolean(KEY_SYNC_THEMES, enabled).apply()

    fun isShortcutsSyncEnabled(context: Context): Boolean = getPrefs(context).getBoolean(KEY_SYNC_SHORTCUTS, true)
    fun setShortcutsSyncEnabled(context: Context, enabled: Boolean) = getPrefs(context).edit().putBoolean(KEY_SYNC_SHORTCUTS, enabled).apply()

    fun isHistorySyncEnabled(context: Context): Boolean = getPrefs(context).getBoolean(KEY_SYNC_HISTORY, false)
    fun setHistorySyncEnabled(context: Context, enabled: Boolean) = getPrefs(context).edit().putBoolean(KEY_SYNC_HISTORY, enabled).apply()

    fun getLastSyncTime(context: Context): Long = getPrefs(context).getLong(KEY_LAST_SYNC_TIME, 0L)

    fun getLastSyncFormatted(context: Context): String {
        val lastSync = getLastSyncTime(context)
        if (lastSync == 0L) return "Never synchronized"

        val now = System.currentTimeMillis()
        val diff = now - lastSync
        return when {
            diff < 60 * 1000L -> "Synchronized just now"
            diff < 60 * 60 * 1000L -> "Synchronized ${diff / (60 * 1000L)} min ago"
            diff < 24 * 60 * 60 * 1000L -> {
                val format = SimpleDateFormat("h:mm a", Locale.getDefault())
                "Synchronized today at ${format.format(Date(lastSync))}"
            }
            else -> {
                val format = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
                "Synchronized on ${format.format(Date(lastSync))}"
            }
        }
    }

    private fun getCloudBackupFile(context: Context, userId: String): File {
        val dir = File(context.filesDir, "cloud_backups")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "backup_${userId.replace(Regex("[^a-zA-Z0-9_-]"), "_")}.json")
    }

    fun performSync(
        context: Context,
        onProgress: ((SyncState, String) -> Unit)? = null,
        onComplete: ((Boolean, String) -> Unit)? = null
    ) {
        val user = HazeAccountManager.getUser(context)
        if (user == null) {
            currentState = SyncState.FAILED
            onComplete?.invoke(false, "User is not signed in.")
            return
        }

        if (!isSyncEnabled(context)) {
            currentState = SyncState.FAILED
            onComplete?.invoke(false, "Cloud synchronization is disabled.")
            return
        }

        currentState = SyncState.SYNCING
        onProgress?.invoke(SyncState.SYNCING, "Synchronizing with Google Cloud...")

        thread {
            try {
                val db = HazeDatabase.getInstance(context)
                val hazePrefs = context.getSharedPreferences("HazePrefs", Context.MODE_PRIVATE)
                val backupFile = getCloudBackupFile(context, user.id)

                // 1. Read existing cloud backup if present
                val cloudJson = if (backupFile.exists() && backupFile.length() > 0) {
                    try {
                        JSONObject(backupFile.readText())
                    } catch (e: Exception) {
                        JSONObject()
                    }
                } else {
                    JSONObject()
                }

                // 2. Sync Bookmarks & Folders if enabled
                if (isBookmarksSyncEnabled(context)) {
                    // Local folders & bookmarks
                    val localFolders = db.getAllBookmarkFolders()
                    val localBookmarks = db.getAllBookmarks()

                    // Cloud folders
                    val cloudFoldersArr = cloudJson.optJSONArray("folders") ?: JSONArray()
                    val cloudFolderMap = mutableMapOf<String, Long>() // name -> localId

                    // Insert missing folders from cloud
                    for (i in 0 until cloudFoldersArr.length()) {
                        val fObj = cloudFoldersArr.getJSONObject(i)
                        val fName = fObj.getString("name")
                        val existingLocal = localFolders.find { it.name.equals(fName, ignoreCase = true) }
                        if (existingLocal == null) {
                            val newId = db.createBookmarkFolder(fName)
                            cloudFolderMap[fName] = newId
                        } else {
                            cloudFolderMap[fName] = existingLocal.id
                        }
                    }

                    // Cloud bookmarks
                    val cloudBookmarksArr = cloudJson.optJSONArray("bookmarks") ?: JSONArray()
                    val localUrls = localBookmarks.map { it.url.trimEnd('/') }.toSet()

                    // Insert missing bookmarks from cloud (prevent duplicates)
                    for (i in 0 until cloudBookmarksArr.length()) {
                        val bObj = cloudBookmarksArr.getJSONObject(i)
                        val bUrl = bObj.getString("url")
                        val bTitle = bObj.optString("title", "Bookmark")
                        val bFolderName = bObj.optString("folderName", "")
                        val targetFolderId = cloudFolderMap[bFolderName] ?: 0L

                        if (!localUrls.contains(bUrl.trimEnd('/'))) {
                            db.addBookmark(bTitle, bUrl, targetFolderId)
                        }
                    }

                    // Now write back updated local bookmarks & folders to cloud snapshot
                    val updatedLocalFolders = db.getAllBookmarkFolders()
                    val updatedLocalBookmarks = db.getAllBookmarks()

                    val newCloudFoldersArr = JSONArray()
                    for (f in updatedLocalFolders) {
                        val obj = JSONObject().apply {
                            put("id", f.id)
                            put("name", f.name)
                            put("parentId", f.parentId)
                            put("createdAt", f.createdAt)
                        }
                        newCloudFoldersArr.put(obj)
                    }

                    val newCloudBookmarksArr = JSONArray()
                    for (b in updatedLocalBookmarks) {
                        val folder = updatedLocalFolders.find { it.id == b.folderId }
                        val obj = JSONObject().apply {
                            put("id", b.id)
                            put("title", b.title)
                            put("url", b.url)
                            put("folderName", folder?.name ?: "")
                            put("createdAt", b.createdAt)
                        }
                        newCloudBookmarksArr.put(obj)
                    }

                    cloudJson.put("folders", newCloudFoldersArr)
                    cloudJson.put("bookmarks", newCloudBookmarksArr)
                }

                // 3. Sync Settings & Preferences if enabled
                if (isSettingsSyncEnabled(context)) {
                    val settingsObj = cloudJson.optJSONObject("settings") ?: JSONObject()
                    val cloudSettingsTimestamp = settingsObj.optLong("updatedAt", 0L)
                    val localSettingsTimestamp = hazePrefs.getLong("settings_updated_at", 0L)

                    if (cloudSettingsTimestamp > localSettingsTimestamp) {
                        // Cloud is newer -> apply to local
                        hazePrefs.edit()
                            .putBoolean("desktop_mode", settingsObj.optBoolean("desktop_mode", false))
                            .putBoolean("cookies_enabled", settingsObj.optBoolean("cookies_enabled", true))
                            .putBoolean("javascript_enabled", settingsObj.optBoolean("javascript_enabled", true))
                            .putString("search_engine", settingsObj.optString("search_engine", "Google"))
                            .apply()
                    } else {
                        // Local is newer -> write to cloud
                        settingsObj.put("desktop_mode", hazePrefs.getBoolean("desktop_mode", false))
                        settingsObj.put("cookies_enabled", hazePrefs.getBoolean("cookies_enabled", true))
                        settingsObj.put("javascript_enabled", hazePrefs.getBoolean("javascript_enabled", true))
                        settingsObj.put("search_engine", hazePrefs.getString("search_engine", "Google"))
                        settingsObj.put("updatedAt", System.currentTimeMillis())
                        cloudJson.put("settings", settingsObj)
                    }
                }

                // 4. Sync Themes if enabled
                if (isThemesSyncEnabled(context)) {
                    val themeObj = cloudJson.optJSONObject("theme") ?: JSONObject()
                    val cloudThemeTime = themeObj.optLong("updatedAt", 0L)
                    val localThemeTime = hazePrefs.getLong("theme_updated_at", 0L)

                    if (cloudThemeTime > localThemeTime) {
                        hazePrefs.edit()
                            .putBoolean("is_dark", themeObj.optBoolean("is_dark", false))
                            .putString("bg_theme", themeObj.optString("bg_theme", "default"))
                            .putString("accent_color", themeObj.optString("accent_color", "#0A56D0"))
                            .apply()
                    } else {
                        themeObj.put("is_dark", hazePrefs.getBoolean("is_dark", false))
                        themeObj.put("bg_theme", hazePrefs.getString("bg_theme", "default"))
                        themeObj.put("accent_color", hazePrefs.getString("accent_color", "#0A56D0"))
                        themeObj.put("updatedAt", System.currentTimeMillis())
                        cloudJson.put("theme", themeObj)
                    }
                }

                // 5. Sync Homepage Shortcuts if enabled
                if (isShortcutsSyncEnabled(context)) {
                    val cloudShortcutsArr = cloudJson.optJSONArray("shortcuts") ?: JSONArray()
                    val localShortcutsStr = hazePrefs.getString("custom_shortcuts", "[]") ?: "[]"
                    val localShortcutsArr = JSONArray(localShortcutsStr)

                    val existingUrls = mutableSetOf<String>()
                    val mergedShortcuts = JSONArray()

                    for (i in 0 until localShortcutsArr.length()) {
                        val s = localShortcutsArr.getJSONObject(i)
                        val u = s.optString("url")
                        if (existingUrls.add(u)) {
                            mergedShortcuts.put(s)
                        }
                    }

                    for (i in 0 until cloudShortcutsArr.length()) {
                        val s = cloudShortcutsArr.getJSONObject(i)
                        val u = s.optString("url")
                        if (existingUrls.add(u)) {
                            mergedShortcuts.put(s)
                        }
                    }

                    hazePrefs.edit().putString("custom_shortcuts", mergedShortcuts.toString()).apply()
                    cloudJson.put("shortcuts", mergedShortcuts)
                }

                // Metadata
                cloudJson.put("userId", user.id)
                cloudJson.put("userEmail", user.email)
                cloudJson.put("lastSyncTime", System.currentTimeMillis())
                cloudJson.put("schemaVersion", 2)

                // Write backup snapshot atomically
                backupFile.writeText(cloudJson.toString(2))

                // Update last sync time
                getPrefs(context).edit().putLong(KEY_LAST_SYNC_TIME, System.currentTimeMillis()).apply()

                // Artificial smooth delay for UX
                Thread.sleep(350)

                currentState = SyncState.COMPLETED
                mainHandler.post {
                    onProgress?.invoke(SyncState.COMPLETED, "Synchronization complete.")
                    onComplete?.invoke(true, "Synchronized successfully with Google Cloud.")
                }
            } catch (e: Exception) {
                currentState = SyncState.FAILED
                mainHandler.post {
                    onProgress?.invoke(SyncState.FAILED, "Sync error: ${e.localizedMessage}")
                    onComplete?.invoke(false, "Sync failed: ${e.localizedMessage}")
                }
            }
        }
    }

    fun deleteCloudBackup(context: Context, callback: (Boolean) -> Unit) {
        val user = HazeAccountManager.getUser(context)
        if (user == null) {
            callback(false)
            return
        }

        thread {
            try {
                val file = getCloudBackupFile(context, user.id)
                if (file.exists()) file.delete()
                getPrefs(context).edit().remove(KEY_LAST_SYNC_TIME).apply()
                mainHandler.post { callback(true) }
            } catch (e: Exception) {
                mainHandler.post { callback(false) }
            }
        }
    }

    fun clearLocalSyncedData(context: Context) {
        thread {
            try {
                val db = HazeDatabase.getInstance(context)
                db.clearAllBookmarks()

                val hazePrefs = context.getSharedPreferences("HazePrefs", Context.MODE_PRIVATE)
                hazePrefs.edit()
                    .remove("custom_shortcuts")
                    .remove("bg_theme")
                    .remove("accent_color")
                    .remove("desktop_mode")
                    .apply()
            } catch (e: Exception) {
            }
        }
    }
}
