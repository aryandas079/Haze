package com.example.haze

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.util.Calendar

data class HistoryItem(
    val id: Long,
    val url: String,
    val title: String,
    val timestamp: Long,
    val visitCount: Int = 1,
    val faviconPath: String? = null
)

data class BookmarkFolder(
    val id: Long,
    val name: String,
    val parentId: Long = 0L,
    val createdAt: Long = System.currentTimeMillis()
)

data class BookmarkItem(
    val id: Long,
    val title: String,
    val url: String,
    val folderId: Long = 0L,
    val createdAt: Long = System.currentTimeMillis(),
    val position: Int = 0,
    val faviconPath: String? = null
)

data class DownloadItem(
    val id: Long,
    val downloadId: Long,
    val fileName: String,
    val filePath: String,
    val fileSize: Long,
    val url: String,
    val mimeType: String,
    val status: String, // QUEUED, DOWNLOADING, PAUSED, COMPLETED, FAILED, CANCELLED
    val timestamp: Long = System.currentTimeMillis()
)

data class RecentSearchItem(
    val id: Long,
    val query: String,
    val timestamp: Long
)

data class PrivacyShieldStats(
    val adsBlocked: Long = 0L,
    val trackersBlocked: Long = 0L,
    val maliciousBlocked: Long = 0L,
    val phishingBlocked: Long = 0L,
    val unwantedScriptsBlocked: Long = 0L,
    val thirdPartyBlocked: Long = 0L,
    val totalBlocked: Long = 0L,
    val dataSavedBytes: Long = 0L,
    val timeSavedMs: Long = 0L,
    val lastUpdated: Long = System.currentTimeMillis()
)

data class BlockedEvent(
    val id: Long = 0L,
    val category: String, // ADS, TRACKERS, MALICIOUS, PHISHING, UNWANTED_SCRIPTS, THIRD_PARTY
    val domain: String,
    val url: String,
    val resourceType: String,
    val bytesSaved: Long,
    val reason: String,
    val timestamp: Long = System.currentTimeMillis()
)

class HazeDatabase private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val DATABASE_NAME = "haze_browser.db"
        private const val DATABASE_VERSION = 3

        @Volatile
        private var instance: HazeDatabase? = null

        fun getInstance(context: Context): HazeDatabase {
            return instance ?: synchronized(this) {
                instance ?: HazeDatabase(context).also { instance = it }
            }
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        // 1. History Table
        db.execSQL(
            """
            CREATE TABLE history (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                url TEXT NOT NULL,
                title TEXT,
                timestamp INTEGER NOT NULL,
                visit_count INTEGER DEFAULT 1,
                favicon_path TEXT
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_history_timestamp ON history(timestamp DESC)")
        db.execSQL("CREATE INDEX idx_history_url ON history(url)")

        // 2. Bookmark Folders Table
        db.execSQL(
            """
            CREATE TABLE bookmark_folders (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                parent_id INTEGER DEFAULT 0,
                created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )

        // 3. Bookmarks Table
        db.execSQL(
            """
            CREATE TABLE bookmarks (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                title TEXT NOT NULL,
                url TEXT NOT NULL,
                folder_id INTEGER DEFAULT 0,
                created_at INTEGER NOT NULL,
                position INTEGER DEFAULT 0,
                favicon_path TEXT
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_bookmarks_folder ON bookmarks(folder_id)")

        // 4. Downloads Table
        db.execSQL(
            """
            CREATE TABLE downloads (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                download_id INTEGER UNIQUE,
                file_name TEXT NOT NULL,
                file_path TEXT,
                file_size INTEGER DEFAULT 0,
                url TEXT NOT NULL,
                mime_type TEXT,
                status TEXT NOT NULL,
                timestamp INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_downloads_timestamp ON downloads(timestamp DESC)")

        // 5. Recent Searches Table
        db.execSQL(
            """
            CREATE TABLE recent_searches (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                query TEXT UNIQUE NOT NULL,
                timestamp INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_searches_timestamp ON recent_searches(timestamp DESC)")

        // Insert initial default bookmark folder
        val values = ContentValues().apply {
            put("name", "Mobile Bookmarks")
            put("parent_id", 0)
            put("created_at", System.currentTimeMillis())
        }
        db.insert("bookmark_folders", null, values)

        // 6. Privacy Shield Stats Table
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS privacy_shield_stats (
                id INTEGER PRIMARY KEY CHECK (id = 1),
                ads_blocked INTEGER DEFAULT 0,
                trackers_blocked INTEGER DEFAULT 0,
                malicious_blocked INTEGER DEFAULT 0,
                phishing_blocked INTEGER DEFAULT 0,
                unwanted_scripts_blocked INTEGER DEFAULT 0,
                third_party_blocked INTEGER DEFAULT 0,
                total_blocked INTEGER DEFAULT 0,
                data_saved_bytes INTEGER DEFAULT 0,
                time_saved_ms INTEGER DEFAULT 0,
                last_updated INTEGER DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL("INSERT OR IGNORE INTO privacy_shield_stats (id, ads_blocked, trackers_blocked, malicious_blocked, phishing_blocked, unwanted_scripts_blocked, third_party_blocked, total_blocked, data_saved_bytes, time_saved_ms, last_updated) VALUES (1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)")

        // 7. Privacy Shield Events Table (recent blocking events)
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS privacy_shield_events (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                category TEXT NOT NULL,
                domain TEXT NOT NULL,
                url TEXT NOT NULL,
                resource_type TEXT NOT NULL,
                bytes_saved INTEGER DEFAULT 0,
                reason TEXT,
                timestamp INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_shield_events_ts ON privacy_shield_events(timestamp DESC)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_shield_events_cat ON privacy_shield_events(category)")

        // 8. Privacy Shield Whitelisted Domains / Exceptions Table
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS privacy_shield_exceptions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                domain TEXT UNIQUE NOT NULL,
                created_at INTEGER NOT NULL,
                is_whitelisted INTEGER DEFAULT 1
            )
            """.trimIndent()
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            try {
                db.execSQL("ALTER TABLE history ADD COLUMN favicon_path TEXT")
            } catch (ignored: Exception) {}
            try {
                db.execSQL("ALTER TABLE bookmarks ADD COLUMN favicon_path TEXT")
            } catch (ignored: Exception) {}
        }
        if (oldVersion < 3) {
            createPrivacyTables(db)
        }
    }

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        // Safety migration in case of version mismatches
        try {
            db.execSQL("ALTER TABLE history ADD COLUMN favicon_path TEXT")
        } catch (ignored: Exception) {}
        try {
            db.execSQL("ALTER TABLE bookmarks ADD COLUMN favicon_path TEXT")
        } catch (ignored: Exception) {}
        createPrivacyTables(db)
    }

    private fun createPrivacyTables(db: SQLiteDatabase) {
        try {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS privacy_shield_stats (
                    id INTEGER PRIMARY KEY CHECK (id = 1),
                    ads_blocked INTEGER DEFAULT 0,
                    trackers_blocked INTEGER DEFAULT 0,
                    malicious_blocked INTEGER DEFAULT 0,
                    phishing_blocked INTEGER DEFAULT 0,
                    unwanted_scripts_blocked INTEGER DEFAULT 0,
                    third_party_blocked INTEGER DEFAULT 0,
                    total_blocked INTEGER DEFAULT 0,
                    data_saved_bytes INTEGER DEFAULT 0,
                    time_saved_ms INTEGER DEFAULT 0,
                    last_updated INTEGER DEFAULT 0
                )
                """.trimIndent()
            )
            db.execSQL("INSERT OR IGNORE INTO privacy_shield_stats (id, ads_blocked, trackers_blocked, malicious_blocked, phishing_blocked, unwanted_scripts_blocked, third_party_blocked, total_blocked, data_saved_bytes, time_saved_ms, last_updated) VALUES (1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)")
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS privacy_shield_events (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    category TEXT NOT NULL,
                    domain TEXT NOT NULL,
                    url TEXT NOT NULL,
                    resource_type TEXT NOT NULL,
                    bytes_saved INTEGER DEFAULT 0,
                    reason TEXT,
                    timestamp INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_shield_events_ts ON privacy_shield_events(timestamp DESC)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_shield_events_cat ON privacy_shield_events(category)")
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS privacy_shield_exceptions (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    domain TEXT UNIQUE NOT NULL,
                    created_at INTEGER NOT NULL,
                    is_whitelisted INTEGER DEFAULT 1
                )
                """.trimIndent()
            )
        } catch (ignored: Exception) {}
    }

    // ==========================================
    // HISTORY METHODS
    // ==========================================

    fun addHistory(url: String, title: String, isPrivate: Boolean, faviconPath: String? = null) {
        if (isPrivate || url.isEmpty() || url.startsWith("haze://") || url.startsWith("about:")) return
        try {
            val db = writableDatabase
            val cursor = db.rawQuery("SELECT id, visit_count, favicon_path FROM history WHERE url = ? ORDER BY timestamp DESC LIMIT 1", arrayOf(url))
            if (cursor.moveToFirst()) {
                val id = cursor.getLong(0)
                val count = cursor.getInt(1)
                val existingFavicon = cursor.getString(2)
                cursor.close()

                val cv = ContentValues().apply {
                    put("title", if (title.isNotEmpty()) title else url)
                    put("timestamp", System.currentTimeMillis())
                    put("visit_count", count + 1)
                    if (faviconPath != null) {
                        put("favicon_path", faviconPath)
                    } else if (existingFavicon != null) {
                        put("favicon_path", existingFavicon)
                    }
                }
                db.update("history", cv, "id = ?", arrayOf(id.toString()))
            } else {
                cursor.close()
                val cv = ContentValues().apply {
                    put("url", url)
                    put("title", if (title.isNotEmpty()) title else url)
                    put("timestamp", System.currentTimeMillis())
                    put("visit_count", 1)
                    if (faviconPath != null) put("favicon_path", faviconPath)
                }
                db.insert("history", null, cv)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun updateHistoryFavicon(url: String, faviconPath: String) {
        try {
            val cv = ContentValues().apply {
                put("favicon_path", faviconPath)
            }
            writableDatabase.update("history", cv, "url = ?", arrayOf(url))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun searchHistory(query: String): List<HistoryItem> {
        val list = mutableListOf<HistoryItem>()
        try {
            val db = readableDatabase
            val pattern = "%$query%"
            val cursor = db.rawQuery(
                "SELECT id, url, title, timestamp, visit_count, favicon_path FROM history WHERE title LIKE ? OR url LIKE ? ORDER BY timestamp DESC LIMIT 150",
                arrayOf(pattern, pattern)
            )
            while (cursor.moveToNext()) {
                list.add(
                    HistoryItem(
                        id = cursor.getLong(0),
                        url = cursor.getString(1),
                        title = cursor.getString(2) ?: cursor.getString(1),
                        timestamp = cursor.getLong(3),
                        visitCount = cursor.getInt(4),
                        faviconPath = cursor.getString(5)
                    )
                )
            }
            cursor.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    fun getAllHistory(): List<HistoryItem> {
        val list = mutableListOf<HistoryItem>()
        try {
            val db = readableDatabase
            val cursor = db.rawQuery(
                "SELECT id, url, title, timestamp, visit_count, favicon_path FROM history ORDER BY timestamp DESC LIMIT 400",
                null
            )
            while (cursor.moveToNext()) {
                list.add(
                    HistoryItem(
                        id = cursor.getLong(0),
                        url = cursor.getString(1),
                        title = cursor.getString(2) ?: cursor.getString(1),
                        timestamp = cursor.getLong(3),
                        visitCount = cursor.getInt(4),
                        faviconPath = cursor.getString(5)
                    )
                )
            }
            cursor.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    fun getHistoryGrouped(): Map<String, List<HistoryItem>> {
        val all = getAllHistory()
        val calendar = Calendar.getInstance()

        // Start of today
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        val todayStart = calendar.timeInMillis

        // Start of yesterday
        calendar.add(Calendar.DAY_OF_YEAR, -1)
        val yesterdayStart = calendar.timeInMillis

        // Start of 7 days ago
        calendar.add(Calendar.DAY_OF_YEAR, -6)
        val sevenDaysStart = calendar.timeInMillis

        val grouped = linkedMapOf<String, MutableList<HistoryItem>>()
        grouped["Today"] = mutableListOf()
        grouped["Yesterday"] = mutableListOf()
        grouped["Last 7 Days"] = mutableListOf()
        grouped["Older"] = mutableListOf()

        for (item in all) {
            when {
                item.timestamp >= todayStart -> grouped["Today"]!!.add(item)
                item.timestamp >= yesterdayStart -> grouped["Yesterday"]!!.add(item)
                item.timestamp >= sevenDaysStart -> grouped["Last 7 Days"]!!.add(item)
                else -> grouped["Older"]!!.add(item)
            }
        }

        return grouped.filterValues { it.isNotEmpty() }
    }

    fun deleteHistoryItem(id: Long): Boolean {
        return try {
            writableDatabase.delete("history", "id = ?", arrayOf(id.toString())) > 0
        } catch (e: Exception) {
            false
        }
    }

    fun deleteHistoryItems(ids: List<Long>): Int {
        if (ids.isEmpty()) return 0
        return try {
            val db = writableDatabase
            db.beginTransaction()
            var count = 0
            for (id in ids) {
                count += db.delete("history", "id = ?", arrayOf(id.toString()))
            }
            db.setTransactionSuccessful()
            db.endTransaction()
            count
        } catch (e: Exception) {
            0
        }
    }

    fun clearHistoryRange(sinceMillis: Long): Int {
        return try {
            writableDatabase.delete("history", "timestamp >= ?", arrayOf(sinceMillis.toString()))
        } catch (e: Exception) {
            0
        }
    }

    fun clearAllHistory(): Boolean {
        return try {
            writableDatabase.delete("history", null, null)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun clearAllBookmarks(): Boolean {
        return try {
            writableDatabase.delete("bookmarks", null, null)
            writableDatabase.delete("bookmark_folders", null, null)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun clearAllDownloads(): Boolean {
        return try {
            writableDatabase.delete("downloads", null, null)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun clearDownloadsRange(sinceMillis: Long): Int {
        return try {
            writableDatabase.delete("downloads", "timestamp >= ?", arrayOf(sinceMillis.toString()))
        } catch (e: Exception) {
            0
        }
    }

    // ==========================================
    // BOOKMARKS & FOLDERS METHODS
    // ==========================================

    fun addBookmark(title: String, url: String, folderId: Long = 0L, faviconPath: String? = null): Long {
        return try {
            val db = writableDatabase
            val cursor = db.rawQuery("SELECT id FROM bookmarks WHERE url = ? AND folder_id = ?", arrayOf(url, folderId.toString()))
            if (cursor.moveToFirst()) {
                val existingId = cursor.getLong(0)
                cursor.close()
                val cv = ContentValues().apply {
                    put("title", title)
                    if (faviconPath != null) put("favicon_path", faviconPath)
                }
                db.update("bookmarks", cv, "id = ?", arrayOf(existingId.toString()))
                return existingId
            }
            cursor.close()

            val cv = ContentValues().apply {
                put("title", title)
                put("url", url)
                put("folder_id", folderId)
                put("created_at", System.currentTimeMillis())
                put("position", 0)
                if (faviconPath != null) put("favicon_path", faviconPath)
            }
            db.insert("bookmarks", null, cv)
        } catch (e: Exception) {
            -1L
        }
    }

    fun updateBookmark(id: Long, newTitle: String, newUrl: String, newFolderId: Long): Boolean {
        return try {
            val cv = ContentValues().apply {
                put("title", newTitle)
                put("url", newUrl)
                put("folder_id", newFolderId)
            }
            writableDatabase.update("bookmarks", cv, "id = ?", arrayOf(id.toString())) > 0
        } catch (e: Exception) {
            false
        }
    }

    fun moveBookmarks(ids: List<Long>, targetFolderId: Long): Boolean {
        if (ids.isEmpty()) return false
        return try {
            val db = writableDatabase
            db.beginTransaction()
            val cv = ContentValues().apply { put("folder_id", targetFolderId) }
            for (id in ids) {
                db.update("bookmarks", cv, "id = ?", arrayOf(id.toString()))
            }
            db.setTransactionSuccessful()
            db.endTransaction()
            true
        } catch (e: Exception) {
            false
        }
    }

    fun isBookmarked(url: String): Boolean {
        if (url.isEmpty() || url.startsWith("haze://")) return false
        return try {
            val cursor = readableDatabase.rawQuery("SELECT id FROM bookmarks WHERE url = ? LIMIT 1", arrayOf(url))
            val exists = cursor.moveToFirst()
            cursor.close()
            exists
        } catch (e: Exception) {
            false
        }
    }

    fun deleteBookmark(id: Long): Boolean {
        return try {
            writableDatabase.delete("bookmarks", "id = ?", arrayOf(id.toString())) > 0
        } catch (e: Exception) {
            false
        }
    }

    fun deleteBookmarks(ids: List<Long>): Boolean {
        if (ids.isEmpty()) return false
        return try {
            val db = writableDatabase
            db.beginTransaction()
            for (id in ids) {
                db.delete("bookmarks", "id = ?", arrayOf(id.toString()))
            }
            db.setTransactionSuccessful()
            db.endTransaction()
            true
        } catch (e: Exception) {
            false
        }
    }

    fun getBookmarks(folderId: Long): List<BookmarkItem> {
        val list = mutableListOf<BookmarkItem>()
        try {
            val cursor = readableDatabase.rawQuery(
                "SELECT id, title, url, folder_id, created_at, position, favicon_path FROM bookmarks WHERE folder_id = ? ORDER BY position ASC, id DESC",
                arrayOf(folderId.toString())
            )
            while (cursor.moveToNext()) {
                list.add(
                    BookmarkItem(
                        id = cursor.getLong(0),
                        title = cursor.getString(1),
                        url = cursor.getString(2),
                        folderId = cursor.getLong(3),
                        createdAt = cursor.getLong(4),
                        position = cursor.getInt(5),
                        faviconPath = cursor.getString(6)
                    )
                )
            }
            cursor.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    fun getAllBookmarks(): List<BookmarkItem> {
        val list = mutableListOf<BookmarkItem>()
        try {
            val cursor = readableDatabase.rawQuery(
                "SELECT id, title, url, folder_id, created_at, position, favicon_path FROM bookmarks ORDER BY created_at DESC",
                null
            )
            while (cursor.moveToNext()) {
                list.add(
                    BookmarkItem(
                        id = cursor.getLong(0),
                        title = cursor.getString(1),
                        url = cursor.getString(2),
                        folderId = cursor.getLong(3),
                        createdAt = cursor.getLong(4),
                        position = cursor.getInt(5),
                        faviconPath = cursor.getString(6)
                    )
                )
            }
            cursor.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    fun getAllBookmarkFolders(): List<BookmarkFolder> = getAllFolders()

    fun createBookmarkFolder(name: String, parentId: Long = 0L): Long = createFolder(name, parentId)

    fun searchBookmarks(query: String): List<BookmarkItem> {
        val list = mutableListOf<BookmarkItem>()
        try {
            val pattern = "%$query%"
            val cursor = readableDatabase.rawQuery(
                "SELECT id, title, url, folder_id, created_at, position, favicon_path FROM bookmarks WHERE title LIKE ? OR url LIKE ? ORDER BY title ASC LIMIT 100",
                arrayOf(pattern, pattern)
            )
            while (cursor.moveToNext()) {
                list.add(
                    BookmarkItem(
                        id = cursor.getLong(0),
                        title = cursor.getString(1),
                        url = cursor.getString(2),
                        folderId = cursor.getLong(3),
                        createdAt = cursor.getLong(4),
                        position = cursor.getInt(5),
                        faviconPath = cursor.getString(6)
                    )
                )
            }
            cursor.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    fun createFolder(name: String, parentId: Long = 0L): Long {
        return try {
            val cv = ContentValues().apply {
                put("name", name)
                put("parent_id", parentId)
                put("created_at", System.currentTimeMillis())
            }
            writableDatabase.insert("bookmark_folders", null, cv)
        } catch (e: Exception) {
            -1L
        }
    }

    fun renameFolder(folderId: Long, newName: String): Boolean {
        return try {
            val cv = ContentValues().apply { put("name", newName) }
            writableDatabase.update("bookmark_folders", cv, "id = ?", arrayOf(folderId.toString())) > 0
        } catch (e: Exception) {
            false
        }
    }

    fun deleteFolder(folderId: Long): Boolean {
        return try {
            val db = writableDatabase
            // Delete bookmarks inside this folder
            db.delete("bookmarks", "folder_id = ?", arrayOf(folderId.toString()))
            // Recursively delete subfolders
            val children = getSubFolders(folderId)
            for (c in children) {
                deleteFolder(c.id)
            }
            // Delete the folder itself
            db.delete("bookmark_folders", "id = ?", arrayOf(folderId.toString())) > 0
        } catch (e: Exception) {
            false
        }
    }

    fun getSubFolders(parentId: Long): List<BookmarkFolder> {
        val list = mutableListOf<BookmarkFolder>()
        try {
            val cursor = readableDatabase.rawQuery(
                "SELECT id, name, parent_id, created_at FROM bookmark_folders WHERE parent_id = ? ORDER BY name ASC",
                arrayOf(parentId.toString())
            )
            while (cursor.moveToNext()) {
                list.add(
                    BookmarkFolder(
                        id = cursor.getLong(0),
                        name = cursor.getString(1),
                        parentId = cursor.getLong(2),
                        createdAt = cursor.getLong(3)
                    )
                )
            }
            cursor.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    fun getAllFolders(): List<BookmarkFolder> {
        val list = mutableListOf<BookmarkFolder>()
        try {
            val cursor = readableDatabase.rawQuery(
                "SELECT id, name, parent_id, created_at FROM bookmark_folders ORDER BY parent_id ASC, name ASC",
                null
            )
            while (cursor.moveToNext()) {
                list.add(
                    BookmarkFolder(
                        id = cursor.getLong(0),
                        name = cursor.getString(1),
                        parentId = cursor.getLong(2),
                        createdAt = cursor.getLong(3)
                    )
                )
            }
            cursor.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    fun getFolderPath(folderId: Long): List<BookmarkFolder> {
        val path = mutableListOf<BookmarkFolder>()
        var currentId = folderId
        try {
            val db = readableDatabase
            while (currentId != 0L) {
                val cursor = db.rawQuery("SELECT id, name, parent_id, created_at FROM bookmark_folders WHERE id = ?", arrayOf(currentId.toString()))
                if (cursor.moveToFirst()) {
                    val folder = BookmarkFolder(
                        id = cursor.getLong(0),
                        name = cursor.getString(1),
                        parentId = cursor.getLong(2),
                        createdAt = cursor.getLong(3)
                    )
                    path.add(0, folder)
                    currentId = folder.parentId
                } else {
                    break
                }
                cursor.close()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return path
    }

    // ==========================================
    // DOWNLOADS METHODS
    // ==========================================

    fun addDownload(
        downloadId: Long,
        fileName: String,
        filePath: String,
        fileSize: Long,
        url: String,
        mimeType: String,
        status: String = "ACTIVE"
    ): Long {
        return try {
            val cv = ContentValues().apply {
                put("download_id", downloadId)
                put("file_name", fileName)
                put("file_path", filePath)
                put("file_size", fileSize)
                put("url", url)
                put("mime_type", mimeType)
                put("status", status)
                put("timestamp", System.currentTimeMillis())
            }
            writableDatabase.insertWithOnConflict("downloads", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        } catch (e: Exception) {
            -1L
        }
    }

    fun updateDownloadStatus(downloadId: Long, status: String, filePath: String? = null, fileSize: Long? = null): Boolean {
        return try {
            val cv = ContentValues().apply {
                put("status", status)
                if (filePath != null) put("file_path", filePath)
                if (fileSize != null && fileSize > 0) put("file_size", fileSize)
            }
            writableDatabase.update("downloads", cv, "download_id = ?", arrayOf(downloadId.toString())) > 0
        } catch (e: Exception) {
            false
        }
    }

    fun getAllDownloads(statusFilter: String? = null): List<DownloadItem> {
        val list = mutableListOf<DownloadItem>()
        try {
            val query = when (statusFilter?.uppercase()) {
                null, "ALL" -> "SELECT id, download_id, file_name, file_path, file_size, url, mime_type, status, timestamp FROM downloads ORDER BY timestamp DESC"
                "ACTIVE" -> "SELECT id, download_id, file_name, file_path, file_size, url, mime_type, status, timestamp FROM downloads WHERE status IN ('ACTIVE', 'DOWNLOADING', 'QUEUED') ORDER BY timestamp DESC"
                "COMPLETED" -> "SELECT id, download_id, file_name, file_path, file_size, url, mime_type, status, timestamp FROM downloads WHERE status = 'COMPLETED' ORDER BY timestamp DESC"
                "FAILED" -> "SELECT id, download_id, file_name, file_path, file_size, url, mime_type, status, timestamp FROM downloads WHERE status IN ('FAILED', 'CANCELLED') ORDER BY timestamp DESC"
                else -> "SELECT id, download_id, file_name, file_path, file_size, url, mime_type, status, timestamp FROM downloads WHERE status = '$statusFilter' ORDER BY timestamp DESC"
            }
            val cursor = readableDatabase.rawQuery(query, null)
            while (cursor.moveToNext()) {
                list.add(
                    DownloadItem(
                        id = cursor.getLong(0),
                        downloadId = cursor.getLong(1),
                        fileName = cursor.getString(2),
                        filePath = cursor.getString(3) ?: "",
                        fileSize = cursor.getLong(4),
                        url = cursor.getString(5),
                        mimeType = cursor.getString(6) ?: "*/*",
                        status = cursor.getString(7),
                        timestamp = cursor.getLong(8)
                    )
                )
            }
            cursor.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    fun deleteDownload(id: Long): Boolean {
        return try {
            writableDatabase.delete("downloads", "id = ?", arrayOf(id.toString())) > 0
        } catch (e: Exception) {
            false
        }
    }

    fun renameDownload(id: Long, newName: String): Boolean {
        return try {
            val cv = ContentValues().apply { put("file_name", newName) }
            writableDatabase.update("downloads", cv, "id = ?", arrayOf(id.toString())) > 0
        } catch (e: Exception) {
            false
        }
    }

    // ==========================================
    // RECENT SEARCHES METHODS
    // ==========================================

    fun addRecentSearch(query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        try {
            val cv = ContentValues().apply {
                put("query", q)
                put("timestamp", System.currentTimeMillis())
            }
            writableDatabase.insertWithOnConflict("recent_searches", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun getRecentSearches(limit: Int = 12): List<RecentSearchItem> {
        val list = mutableListOf<RecentSearchItem>()
        try {
            val cursor = readableDatabase.rawQuery(
                "SELECT id, query, timestamp FROM recent_searches ORDER BY timestamp DESC LIMIT ?",
                arrayOf(limit.toString())
            )
            while (cursor.moveToNext()) {
                list.add(
                    RecentSearchItem(
                        id = cursor.getLong(0),
                        query = cursor.getString(1),
                        timestamp = cursor.getLong(2)
                    )
                )
            }
            cursor.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    fun deleteRecentSearch(query: String): Boolean {
        return try {
            writableDatabase.delete("recent_searches", "query = ?", arrayOf(query)) > 0
        } catch (e: Exception) {
            false
        }
    }

    fun clearRecentSearches(): Boolean {
        return try {
            writableDatabase.delete("recent_searches", null, null)
            true
        } catch (e: Exception) {
            false
        }
    }

    // ==========================================
    // PRIVACY SHIELD METHODS
    // ==========================================

    fun getPrivacyStats(): PrivacyShieldStats {
        try {
            val cursor = readableDatabase.rawQuery(
                "SELECT ads_blocked, trackers_blocked, malicious_blocked, phishing_blocked, unwanted_scripts_blocked, third_party_blocked, total_blocked, data_saved_bytes, time_saved_ms, last_updated FROM privacy_shield_stats WHERE id = 1",
                null
            )
            if (cursor.moveToFirst()) {
                val stats = PrivacyShieldStats(
                    adsBlocked = cursor.getLong(0),
                    trackersBlocked = cursor.getLong(1),
                    maliciousBlocked = cursor.getLong(2),
                    phishingBlocked = cursor.getLong(3),
                    unwantedScriptsBlocked = cursor.getLong(4),
                    thirdPartyBlocked = cursor.getLong(5),
                    totalBlocked = cursor.getLong(6),
                    dataSavedBytes = cursor.getLong(7),
                    timeSavedMs = cursor.getLong(8),
                    lastUpdated = cursor.getLong(9)
                )
                cursor.close()
                return stats
            }
            cursor.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return PrivacyShieldStats()
    }

    fun incrementPrivacyStats(category: String, bytesSaved: Long, timeSavedMs: Long) {
        try {
            val db = writableDatabase
            val column = when (category) {
                "ADS" -> "ads_blocked"
                "TRACKERS" -> "trackers_blocked"
                "MALICIOUS" -> "malicious_blocked"
                "PHISHING" -> "phishing_blocked"
                "UNWANTED_SCRIPTS" -> "unwanted_scripts_blocked"
                "THIRD_PARTY" -> "third_party_blocked"
                else -> "total_blocked"
            }
            val sql = """
                UPDATE privacy_shield_stats 
                SET $column = $column + 1,
                    total_blocked = total_blocked + 1,
                    data_saved_bytes = data_saved_bytes + ?,
                    time_saved_ms = time_saved_ms + ?,
                    last_updated = ?
                WHERE id = 1
            """.trimIndent()
            db.execSQL(sql, arrayOf(bytesSaved, timeSavedMs, System.currentTimeMillis()))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun recordBlockedEvent(event: BlockedEvent) {
        try {
            val db = writableDatabase
            val cv = ContentValues().apply {
                put("category", event.category)
                put("domain", event.domain)
                put("url", event.url)
                put("resource_type", event.resourceType)
                put("bytes_saved", event.bytesSaved)
                put("reason", event.reason)
                put("timestamp", event.timestamp)
            }
            db.insert("privacy_shield_events", null, cv)

            // Cap the table to the latest 500 events to prevent growth
            db.execSQL("DELETE FROM privacy_shield_events WHERE id NOT IN (SELECT id FROM privacy_shield_events ORDER BY timestamp DESC LIMIT 500)")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun getRecentBlockedEvents(limit: Int = 100, category: String? = null): List<BlockedEvent> {
        val list = mutableListOf<BlockedEvent>()
        try {
            val query = if (category != null) {
                "SELECT id, category, domain, url, resource_type, bytes_saved, reason, timestamp FROM privacy_shield_events WHERE category = ? ORDER BY timestamp DESC LIMIT ?"
            } else {
                "SELECT id, category, domain, url, resource_type, bytes_saved, reason, timestamp FROM privacy_shield_events ORDER BY timestamp DESC LIMIT ?"
            }
            val args = if (category != null) arrayOf(category, limit.toString()) else arrayOf(limit.toString())
            val cursor = readableDatabase.rawQuery(query, args)
            while (cursor.moveToNext()) {
                list.add(
                    BlockedEvent(
                        id = cursor.getLong(0),
                        category = cursor.getString(1),
                        domain = cursor.getString(2),
                        url = cursor.getString(3),
                        resourceType = cursor.getString(4),
                        bytesSaved = cursor.getLong(5),
                        reason = cursor.getString(6) ?: "",
                        timestamp = cursor.getLong(7)
                    )
                )
            }
            cursor.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    fun resetPrivacyStats(): Boolean {
        return try {
            val db = writableDatabase
            db.execSQL("UPDATE privacy_shield_stats SET ads_blocked = 0, trackers_blocked = 0, malicious_blocked = 0, phishing_blocked = 0, unwanted_scripts_blocked = 0, third_party_blocked = 0, total_blocked = 0, data_saved_bytes = 0, time_saved_ms = 0, last_updated = ? WHERE id = 1", arrayOf(System.currentTimeMillis()))
            db.delete("privacy_shield_events", null, null)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun getWhitelistedDomains(): List<String> {
        val list = mutableListOf<String>()
        try {
            val cursor = readableDatabase.rawQuery("SELECT domain FROM privacy_shield_exceptions WHERE is_whitelisted = 1 ORDER BY domain ASC", null)
            while (cursor.moveToNext()) {
                list.add(cursor.getString(0))
            }
            cursor.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    fun isDomainWhitelisted(domain: String): Boolean {
        if (domain.isEmpty()) return false
        val cleanDomain = domain.removePrefix("www.").lowercase()
        try {
            val cursor = readableDatabase.rawQuery(
                "SELECT id FROM privacy_shield_exceptions WHERE (domain = ? OR domain = ?) AND is_whitelisted = 1 LIMIT 1",
                arrayOf(domain.lowercase(), cleanDomain)
            )
            val exists = cursor.moveToFirst()
            cursor.close()
            return exists
        } catch (e: Exception) {
            return false
        }
    }

    fun setDomainWhitelisted(domain: String, whitelisted: Boolean): Boolean {
        val cleanDomain = domain.trim().removePrefix("www.").lowercase()
        if (cleanDomain.isEmpty()) return false
        return try {
            val db = writableDatabase
            if (whitelisted) {
                val cv = ContentValues().apply {
                    put("domain", cleanDomain)
                    put("created_at", System.currentTimeMillis())
                    put("is_whitelisted", 1)
                }
                db.insertWithOnConflict("privacy_shield_exceptions", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            } else {
                db.delete("privacy_shield_exceptions", "domain = ?", arrayOf(cleanDomain))
            }
            HazePrivacyShieldEngine.updateWhitelistCache(cleanDomain, whitelisted)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun getProtectionHistory(timeRange: String, categoryFilter: String?): Pair<FloatArray, Array<String>> {
        val catClause = when (categoryFilter?.uppercase()) {
            "ADS" -> "ADS"
            "TRACKERS" -> "TRACKERS"
            "CDN / OTHER", "CDN", "THIRD_PARTY" -> "THIRD_PARTY"
            else -> null
        }

        val now = System.currentTimeMillis()
        val numBuckets = when (timeRange) {
            "24 hours" -> 6
            "1 month" -> 4
            "1 year" -> 4
            else -> 7 // "7 days"
        }

        val counts = LongArray(numBuckets) { 0L }
        val labels = Array(numBuckets) { "" }

        try {
            val db = readableDatabase
            val calendar = java.util.Calendar.getInstance()

            when (timeRange) {
                "24 hours" -> {
                    val fourHoursMs = 4 * 3600 * 1000L
                    val dayFormat = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
                    for (i in 0 until 6) {
                        val endTime = now - (5 - i) * fourHoursMs
                        val startTime = endTime - fourHoursMs
                        calendar.timeInMillis = endTime
                        labels[i] = dayFormat.format(calendar.time)

                        val query = if (catClause != null) {
                            "SELECT COUNT(*) FROM privacy_shield_events WHERE timestamp >= ? AND timestamp < ? AND category = ?"
                        } else {
                            "SELECT COUNT(*) FROM privacy_shield_events WHERE timestamp >= ? AND timestamp < ?"
                        }
                        val args = if (catClause != null) arrayOf(startTime.toString(), endTime.toString(), catClause) else arrayOf(startTime.toString(), endTime.toString())
                        val cursor = db.rawQuery(query, args)
                        if (cursor.moveToFirst()) counts[i] = cursor.getLong(0)
                        cursor.close()
                    }
                }
                "1 month" -> {
                    val weekMs = 7 * 24 * 3600 * 1000L
                    for (i in 0 until 4) {
                        val endTime = now - (3 - i) * weekMs
                        val startTime = endTime - weekMs
                        labels[i] = "Wk ${i + 1}"

                        val query = if (catClause != null) {
                            "SELECT COUNT(*) FROM privacy_shield_events WHERE timestamp >= ? AND timestamp < ? AND category = ?"
                        } else {
                            "SELECT COUNT(*) FROM privacy_shield_events WHERE timestamp >= ? AND timestamp < ?"
                        }
                        val args = if (catClause != null) arrayOf(startTime.toString(), endTime.toString(), catClause) else arrayOf(startTime.toString(), endTime.toString())
                        val cursor = db.rawQuery(query, args)
                        if (cursor.moveToFirst()) counts[i] = cursor.getLong(0)
                        cursor.close()
                    }
                }
                "1 year" -> {
                    val quarterMs = 91 * 24 * 3600 * 1000L
                    for (i in 0 until 4) {
                        val endTime = now - (3 - i) * quarterMs
                        val startTime = endTime - quarterMs
                        labels[i] = "Q${i + 1}"

                        val query = if (catClause != null) {
                            "SELECT COUNT(*) FROM privacy_shield_events WHERE timestamp >= ? AND timestamp < ? AND category = ?"
                        } else {
                            "SELECT COUNT(*) FROM privacy_shield_events WHERE timestamp >= ? AND timestamp < ?"
                        }
                        val args = if (catClause != null) arrayOf(startTime.toString(), endTime.toString(), catClause) else arrayOf(startTime.toString(), endTime.toString())
                        val cursor = db.rawQuery(query, args)
                        if (cursor.moveToFirst()) counts[i] = cursor.getLong(0)
                        cursor.close()
                    }
                }
                else -> { // "7 days"
                    val dayMs = 24 * 3600 * 1000L
                    val dayFormat = java.text.SimpleDateFormat("EEE", java.util.Locale.US)
                    for (i in 0 until 7) {
                        val endTime = now - (6 - i) * dayMs
                        val startTime = endTime - dayMs
                        calendar.timeInMillis = endTime
                        labels[i] = dayFormat.format(calendar.time)

                        val query = if (catClause != null) {
                            "SELECT COUNT(*) FROM privacy_shield_events WHERE timestamp >= ? AND timestamp < ? AND category = ?"
                        } else {
                            "SELECT COUNT(*) FROM privacy_shield_events WHERE timestamp >= ? AND timestamp < ?"
                        }
                        val args = if (catClause != null) arrayOf(startTime.toString(), endTime.toString(), catClause) else arrayOf(startTime.toString(), endTime.toString())
                        val cursor = db.rawQuery(query, args)
                        if (cursor.moveToFirst()) counts[i] = cursor.getLong(0)
                        cursor.close()
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Normalize points for curve
        val maxVal = counts.maxOrNull() ?: 0L
        val points = FloatArray(numBuckets)
        if (maxVal == 0L) {
            for (i in 0 until numBuckets) {
                points[i] = 0.05f
            }
        } else {
            for (i in 0 until numBuckets) {
                points[i] = (counts[i].toFloat() / maxVal.toFloat()).coerceIn(0.08f, 0.95f)
            }
        }

        return Pair(points, labels)
    }
}
