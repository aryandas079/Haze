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
    val visitCount: Int = 1
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
    val position: Int = 0
)

data class DownloadItem(
    val id: Long,
    val downloadId: Long,
    val fileName: String,
    val filePath: String,
    val fileSize: Long,
    val url: String,
    val mimeType: String,
    val status: String, // ACTIVE, COMPLETED, FAILED, PAUSED
    val timestamp: Long = System.currentTimeMillis()
)

data class RecentSearchItem(
    val id: Long,
    val query: String,
    val timestamp: Long
)

class HazeDatabase private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val DATABASE_NAME = "haze_browser.db"
        private const val DATABASE_VERSION = 1

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
                visit_count INTEGER DEFAULT 1
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
                position INTEGER DEFAULT 0
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

        // Insert initial default bookmark folder if desired
        val values = ContentValues().apply {
            put("name", "Mobile Bookmarks")
            put("parent_id", 0)
            put("created_at", System.currentTimeMillis())
        }
        db.insert("bookmark_folders", null, values)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Future migrations
    }

    // ==========================================
    // HISTORY METHODS
    // ==========================================

    fun addHistory(url: String, title: String, isPrivate: Boolean) {
        if (isPrivate || url.isEmpty() || url.startsWith("haze://") || url.startsWith("about:")) return
        try {
            val db = writableDatabase
            // Check if URL was visited very recently (update visit count and timestamp)
            val cursor = db.rawQuery("SELECT id, visit_count FROM history WHERE url = ? ORDER BY timestamp DESC LIMIT 1", arrayOf(url))
            if (cursor.moveToFirst()) {
                val id = cursor.getLong(0)
                val count = cursor.getInt(1)
                cursor.close()

                val cv = ContentValues().apply {
                    put("title", if (title.isNotEmpty()) title else url)
                    put("timestamp", System.currentTimeMillis())
                    put("visit_count", count + 1)
                }
                db.update("history", cv, "id = ?", arrayOf(id.toString()))
            } else {
                cursor.close()
                val cv = ContentValues().apply {
                    put("url", url)
                    put("title", if (title.isNotEmpty()) title else url)
                    put("timestamp", System.currentTimeMillis())
                    put("visit_count", 1)
                }
                db.insert("history", null, cv)
            }
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
                "SELECT id, url, title, timestamp, visit_count FROM history WHERE title LIKE ? OR url LIKE ? ORDER BY timestamp DESC LIMIT 100",
                arrayOf(pattern, pattern)
            )
            while (cursor.moveToNext()) {
                list.add(
                    HistoryItem(
                        id = cursor.getLong(0),
                        url = cursor.getString(1),
                        title = cursor.getString(2) ?: cursor.getString(1),
                        timestamp = cursor.getLong(3),
                        visitCount = cursor.getInt(4)
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
                "SELECT id, url, title, timestamp, visit_count FROM history ORDER BY timestamp DESC LIMIT 300",
                null
            )
            while (cursor.moveToNext()) {
                list.add(
                    HistoryItem(
                        id = cursor.getLong(0),
                        url = cursor.getString(1),
                        title = cursor.getString(2) ?: cursor.getString(1),
                        timestamp = cursor.getLong(3),
                        visitCount = cursor.getInt(4)
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

        // Return only sections that have items
        return grouped.filterValues { it.isNotEmpty() }
    }

    fun deleteHistoryItem(id: Long): Boolean {
        return try {
            writableDatabase.delete("history", "id = ?", arrayOf(id.toString())) > 0
        } catch (e: Exception) {
            false
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

    // ==========================================
    // BOOKMARKS & FOLDERS METHODS
    // ==========================================

    fun addBookmark(title: String, url: String, folderId: Long = 0L): Long {
        return try {
            val db = writableDatabase
            // Check if already bookmarked in this folder
            val cursor = db.rawQuery("SELECT id FROM bookmarks WHERE url = ? AND folder_id = ?", arrayOf(url, folderId.toString()))
            if (cursor.moveToFirst()) {
                val existingId = cursor.getLong(0)
                cursor.close()
                // Update title
                val cv = ContentValues().apply { put("title", title) }
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

    fun getBookmarks(folderId: Long): List<BookmarkItem> {
        val list = mutableListOf<BookmarkItem>()
        try {
            val cursor = readableDatabase.rawQuery(
                "SELECT id, title, url, folder_id, created_at, position FROM bookmarks WHERE folder_id = ? ORDER BY position ASC, id DESC",
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
                        position = cursor.getInt(5)
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
            // Delete child folders
            db.delete("bookmark_folders", "parent_id = ?", arrayOf(folderId.toString()))
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
            val query = if (statusFilter == null || statusFilter == "ALL") {
                "SELECT id, download_id, file_name, file_path, file_size, url, mime_type, status, timestamp FROM downloads ORDER BY timestamp DESC"
            } else {
                "SELECT id, download_id, file_name, file_path, file_size, url, mime_type, status, timestamp FROM downloads WHERE status = '$statusFilter' ORDER BY timestamp DESC"
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
}
