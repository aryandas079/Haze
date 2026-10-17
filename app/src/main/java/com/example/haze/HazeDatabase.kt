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
    val popupsBlocked: Long = 0L,
    val malwareBlocked: Long = 0L,
    val maliciousBlocked: Long = 0L,
    val phishingBlocked: Long = 0L,
    val unwantedScriptsBlocked: Long = 0L,
    val thirdPartyBlocked: Long = 0L,
    val totalBlocked: Long = 0L,
    val dataSavedBytes: Long = 0L,
    val timeSavedMs: Long = 0L,
    val lastUpdated: Long = System.currentTimeMillis()
)

data class BlockedTodayStats(
    val ads: Long = 0L,
    val trackers: Long = 0L,
    val popups: Long = 0L,
    val malware: Long = 0L,
    val tokensCleaned: Long = 0L,
    val bouncesStopped: Long = 0L,
    val dataSavedBytes: Long = 0L,
    val timeSavedMs: Long = 0L
) {
    val total: Long get() = ads + trackers + popups + malware + tokensCleaned + bouncesStopped
}

data class UserCustomRule(
    val id: Long = 0L,
    val ruleText: String,
    val isCosmetic: Boolean = false,
    val isException: Boolean = false,
    val isEnabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
)

data class FilterSubscription(
    val id: String,
    val name: String,
    val description: String = "",
    val url: String,
    val isRegional: Boolean = false,
    val isCustom: Boolean = false,
    var isEnabled: Boolean = false,
    var ruleCount: Int = 0,
    var lastUpdated: Long = 0L
)

data class BlockedEvent(
    val id: Long = 0L,
    val category: String, // ADS, TRACKERS, POPUPS, MALWARE, MALICIOUS, PHISHING, UNWANTED_SCRIPTS, THIRD_PARTY
    val domain: String,
    val url: String,
    val resourceType: String,
    val bytesSaved: Long,
    val reason: String,
    val timestamp: Long = System.currentTimeMillis()
)

data class PasswordItem(
    val id: Long,
    val site: String,
    val username: String,
    val encryptedPassword: String,
    val title: String = "",
    val note: String = "",
    val isPasskey: Boolean = false
)

data class CreditCardItem(
    val id: Long = 0L,
    val cardholderName: String,
    val encryptedCardNumber: String,
    val expiryMonth: String,
    val expiryYear: String,
    val cardNickname: String = ""
)

data class AddressItem(
    val id: Long = 0L,
    val fullName: String,
    val addressLine1: String,
    val addressLine2: String = "",
    val city: String,
    val state: String = "",
    val postalCode: String,
    val country: String = "",
    val phoneNumber: String = ""
)

data class OfflinePageItem(
    val id: Long,
    val title: String,
    val url: String,
    val content: String,
    val savedAt: Long = System.currentTimeMillis()
)

data class SavedSelectionItem(
    val id: Long = 0L,
    val title: String,
    val url: String,
    val selectedText: String,
    val note: String = "",
    val timestamp: Long = System.currentTimeMillis()
)


class HazeDatabase private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val DATABASE_NAME = "haze_browser.db"
        private const val DATABASE_VERSION = 5

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
        createPasswordTable(db)
        createOfflineTable(db)
        createSavedSelectionsTable(db)
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
        if (oldVersion < 4) {
            createPasswordTable(db)
        }
        if (oldVersion < 5) {
            createPrivacyTables(db)
        }
        createOfflineTable(db)
        createSavedSelectionsTable(db)
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.enableWriteAheadLogging()
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
        createPasswordTable(db)
        try {
            db.execSQL("ALTER TABLE password_items ADD COLUMN note TEXT NOT NULL DEFAULT ''")
        } catch (ignored: Exception) {}
        try {
            db.execSQL("ALTER TABLE password_items ADD COLUMN is_passkey INTEGER NOT NULL DEFAULT 0")
        } catch (ignored: Exception) {}
        createOfflineTable(db)
        createSavedSelectionsTable(db)
        try {
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_history_title ON history(title)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_bookmarks_url ON bookmarks(url)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_bookmarks_title ON bookmarks(title)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_searches_query ON recent_searches(query)")
        } catch (ignored: Exception) {}
    }

    private fun createOfflineTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS offline_pages (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                title TEXT NOT NULL,
                url TEXT UNIQUE NOT NULL,
                content TEXT NOT NULL,
                saved_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_offline_saved_at ON offline_pages(saved_at DESC)")
    }

    private fun createSavedSelectionsTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS saved_selections (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                title TEXT NOT NULL,
                url TEXT NOT NULL,
                selected_text TEXT NOT NULL,
                note TEXT NOT NULL DEFAULT '',
                timestamp INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_saved_selections_ts ON saved_selections(timestamp DESC)")
    }


    private fun createPasswordTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS password_items (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                site TEXT NOT NULL,
                title TEXT NOT NULL DEFAULT '',
                username TEXT NOT NULL,
                encrypted_password TEXT NOT NULL,
                note TEXT NOT NULL DEFAULT '',
                is_passkey INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                UNIQUE(site, username)
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_password_items_site ON password_items(site)")

        // Credit Cards Table
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS credit_cards (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                cardholder_name TEXT NOT NULL,
                encrypted_card_number TEXT NOT NULL,
                expiry_month TEXT NOT NULL,
                expiry_year TEXT NOT NULL,
                card_nickname TEXT NOT NULL DEFAULT '',
                created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )

        // Addresses Table
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS addresses (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                full_name TEXT NOT NULL,
                address_line1 TEXT NOT NULL,
                address_line2 TEXT NOT NULL DEFAULT '',
                city TEXT NOT NULL,
                state TEXT NOT NULL DEFAULT '',
                postal_code TEXT NOT NULL,
                country TEXT NOT NULL DEFAULT '',
                phone_number TEXT NOT NULL DEFAULT '',
                created_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
    }

    fun upsertPassword(
        site: String,
        title: String,
        username: String,
        encryptedPassword: String,
        note: String = "",
        isPasskey: Boolean = false
    ): Long {
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put("site", site.trim())
            put("title", title.trim())
            put("username", username)
            put("encrypted_password", encryptedPassword)
            put("note", note)
            put("is_passkey", if (isPasskey) 1 else 0)
            put("created_at", now)
            put("updated_at", now)
        }
        return writableDatabase.insertWithOnConflict(
            "password_items", null, values, SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun updatePasswordNote(id: Long, note: String): Boolean {
        val values = ContentValues().apply {
            put("note", note)
            put("updated_at", System.currentTimeMillis())
        }
        return writableDatabase.update("password_items", values, "id = ?", arrayOf(id.toString())) > 0
    }

    fun getPasswords(): List<PasswordItem> {
        val result = mutableListOf<PasswordItem>()
        readableDatabase.query(
            "password_items",
            arrayOf("id", "site", "title", "username", "encrypted_password", "note", "is_passkey"),
            null, null, null, null, "site COLLATE NOCASE ASC, username COLLATE NOCASE ASC"
        ).use { cursor ->
            val noteIdx = cursor.getColumnIndex("note")
            val passkeyIdx = cursor.getColumnIndex("is_passkey")
            while (cursor.moveToNext()) {
                val note = if (noteIdx >= 0) cursor.getString(noteIdx) ?: "" else ""
                val isPasskey = if (passkeyIdx >= 0) cursor.getInt(passkeyIdx) == 1 else false
                result += PasswordItem(
                    id = cursor.getLong(0),
                    site = cursor.getString(1),
                    title = cursor.getString(2),
                    username = cursor.getString(3),
                    encryptedPassword = cursor.getString(4),
                    note = note,
                    isPasskey = isPasskey
                )
            }
        }
        return result
    }

    fun deletePassword(id: Long): Boolean =
        writableDatabase.delete("password_items", "id = ?", arrayOf(id.toString())) > 0

    // Credit card CRUD
    fun upsertCreditCard(
        cardholderName: String,
        encryptedNumber: String,
        expiryMonth: String,
        expiryYear: String,
        cardNickname: String = ""
    ): Long {
        val values = ContentValues().apply {
            put("cardholder_name", cardholderName.trim())
            put("encrypted_card_number", encryptedNumber)
            put("expiry_month", expiryMonth.trim())
            put("expiry_year", expiryYear.trim())
            put("card_nickname", cardNickname.trim())
            put("created_at", System.currentTimeMillis())
        }
        return writableDatabase.insert("credit_cards", null, values)
    }

    fun getCreditCards(): List<CreditCardItem> {
        val result = mutableListOf<CreditCardItem>()
        readableDatabase.query(
            "credit_cards",
            arrayOf("id", "cardholder_name", "encrypted_card_number", "expiry_month", "expiry_year", "card_nickname"),
            null, null, null, null, "id DESC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += CreditCardItem(
                    id = cursor.getLong(0),
                    cardholderName = cursor.getString(1),
                    encryptedCardNumber = cursor.getString(2),
                    expiryMonth = cursor.getString(3),
                    expiryYear = cursor.getString(4),
                    cardNickname = cursor.getString(5)
                )
            }
        }
        return result
    }

    fun deleteCreditCard(id: Long): Boolean =
        writableDatabase.delete("credit_cards", "id = ?", arrayOf(id.toString())) > 0

    // Addresses CRUD
    fun upsertAddress(
        fullName: String,
        addressLine1: String,
        addressLine2: String = "",
        city: String,
        state: String = "",
        postalCode: String,
        country: String = "",
        phoneNumber: String = ""
    ): Long {
        val values = ContentValues().apply {
            put("full_name", fullName.trim())
            put("address_line1", addressLine1.trim())
            put("address_line2", addressLine2.trim())
            put("city", city.trim())
            put("state", state.trim())
            put("postal_code", postalCode.trim())
            put("country", country.trim())
            put("phone_number", phoneNumber.trim())
            put("created_at", System.currentTimeMillis())
        }
        return writableDatabase.insert("addresses", null, values)
    }

    fun getAddresses(): List<AddressItem> {
        val result = mutableListOf<AddressItem>()
        readableDatabase.query(
            "addresses",
            arrayOf("id", "full_name", "address_line1", "address_line2", "city", "state", "postal_code", "country", "phone_number"),
            null, null, null, null, "id DESC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += AddressItem(
                    id = cursor.getLong(0),
                    fullName = cursor.getString(1),
                    addressLine1 = cursor.getString(2),
                    addressLine2 = cursor.getString(3),
                    city = cursor.getString(4),
                    state = cursor.getString(5),
                    postalCode = cursor.getString(6),
                    country = cursor.getString(7),
                    phoneNumber = cursor.getString(8)
                )
            }
        }
        return result
    }

    fun deleteAddress(id: Long): Boolean =
        writableDatabase.delete("addresses", "id = ?", arrayOf(id.toString())) > 0

    fun clearAllPasswords(): Int =
        writableDatabase.delete("password_items", null, null)

    fun clearAllCreditCards(): Int =
        writableDatabase.delete("credit_cards", null, null)

    fun clearAllAddresses(): Int =
        writableDatabase.delete("addresses", null, null)

    private fun createPrivacyTables(db: SQLiteDatabase) {
        try {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS privacy_shield_stats (
                    id INTEGER PRIMARY KEY CHECK (id = 1),
                    ads_blocked INTEGER DEFAULT 0,
                    trackers_blocked INTEGER DEFAULT 0,
                    popups_blocked INTEGER DEFAULT 0,
                    malware_blocked INTEGER DEFAULT 0,
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
            try { db.execSQL("ALTER TABLE privacy_shield_stats ADD COLUMN popups_blocked INTEGER DEFAULT 0") } catch (_: Exception) {}
            try { db.execSQL("ALTER TABLE privacy_shield_stats ADD COLUMN malware_blocked INTEGER DEFAULT 0") } catch (_: Exception) {}
            try { db.execSQL("INSERT OR IGNORE INTO privacy_shield_stats (id, ads_blocked, trackers_blocked, popups_blocked, malware_blocked, malicious_blocked, phishing_blocked, unwanted_scripts_blocked, third_party_blocked, total_blocked, data_saved_bytes, time_saved_ms, last_updated) VALUES (1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)") } catch (_: Exception) {}
        } catch (_: Exception) {}

        try {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS privacy_shield_daily_stats (
                    date_key TEXT PRIMARY KEY,
                    ads_blocked INTEGER DEFAULT 0,
                    trackers_blocked INTEGER DEFAULT 0,
                    popups_blocked INTEGER DEFAULT 0,
                    malware_blocked INTEGER DEFAULT 0,
                    tokens_cleaned INTEGER DEFAULT 0,
                    bounces_stopped INTEGER DEFAULT 0,
                    data_saved_bytes INTEGER DEFAULT 0,
                    time_saved_ms INTEGER DEFAULT 0
                )
                """.trimIndent()
            )
            try { db.execSQL("ALTER TABLE privacy_shield_daily_stats ADD COLUMN tokens_cleaned INTEGER DEFAULT 0") } catch (_: Exception) {}
            try { db.execSQL("ALTER TABLE privacy_shield_daily_stats ADD COLUMN bounces_stopped INTEGER DEFAULT 0") } catch (_: Exception) {}
        } catch (_: Exception) {}

        try {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS user_custom_rules (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    rule_text TEXT UNIQUE NOT NULL,
                    is_cosmetic INTEGER DEFAULT 0,
                    is_exception INTEGER DEFAULT 0,
                    is_enabled INTEGER DEFAULT 1,
                    created_at INTEGER NOT NULL
                )
                """.trimIndent()
            )
        } catch (_: Exception) {}

        try {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS custom_filter_subscriptions (
                    id TEXT PRIMARY KEY,
                    name TEXT NOT NULL,
                    description TEXT,
                    url TEXT NOT NULL,
                    is_regional INTEGER DEFAULT 0,
                    is_custom INTEGER DEFAULT 0,
                    is_enabled INTEGER DEFAULT 0,
                    rule_count INTEGER DEFAULT 0,
                    last_updated INTEGER DEFAULT 0
                )
                """.trimIndent()
            )
        } catch (_: Exception) {}

        try {
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
        } catch (_: Exception) {}

        try {
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
        } catch (_: Exception) {}
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
        val grouped = linkedMapOf<String, MutableList<HistoryItem>>()
        
        val calendar = Calendar.getInstance()
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        val todayStart = calendar.timeInMillis

        calendar.add(Calendar.DAY_OF_YEAR, -1)
        val yesterdayStart = calendar.timeInMillis

        calendar.add(Calendar.DAY_OF_YEAR, -5)
        val sixDaysAgoStart = calendar.timeInMillis

        val dayOfWeekFormat = java.text.SimpleDateFormat("EEEE", java.util.Locale.getDefault())
        val dateFormat = java.text.SimpleDateFormat("d MMM", java.util.Locale.getDefault())

        for (item in all) {
            val label = when {
                item.timestamp >= todayStart -> "Today"
                item.timestamp >= yesterdayStart -> "Yesterday"
                item.timestamp >= sixDaysAgoStart -> {
                    val c = Calendar.getInstance()
                    c.timeInMillis = item.timestamp
                    dayOfWeekFormat.format(c.time)
                }
                else -> {
                    val c = Calendar.getInstance()
                    c.timeInMillis = item.timestamp
                    dateFormat.format(c.time)
                }
            }
            grouped.getOrPut(label) { mutableListOf() }.add(item)
        }

        return grouped
    }

    fun deleteHistoryItem(id: Long): Boolean {
        return try {
            writableDatabase.delete("history", "id = ?", arrayOf(id.toString())) > 0
        } catch (e: Exception) {
            false
        }
    }

    fun deleteHistoryByUrl(url: String): Boolean {
        return try {
            writableDatabase.delete("history", "url = ?", arrayOf(url)) > 0
        } catch (_: Exception) {
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

    fun deleteBookmarkByUrl(url: String): Boolean {
        return try {
            writableDatabase.delete("bookmarks", "url = ?", arrayOf(url)) > 0
        } catch (_: Exception) {
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

    fun updateDownloadStatus(downloadId: Long, status: String, filePath: String? = null, fileSize: Long? = null, fileName: String? = null): Boolean {
        return try {
            val cv = ContentValues().apply {
                put("status", status)
                if (filePath != null) put("file_path", filePath)
                if (fileSize != null && fileSize > 0) put("file_size", fileSize)
                if (fileName != null) put("file_name", fileName)
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
                "SELECT ads_blocked, trackers_blocked, popups_blocked, malware_blocked, malicious_blocked, phishing_blocked, unwanted_scripts_blocked, third_party_blocked, total_blocked, data_saved_bytes, time_saved_ms, last_updated FROM privacy_shield_stats WHERE id = 1",
                null
            )
            if (cursor.moveToFirst()) {
                val stats = PrivacyShieldStats(
                    adsBlocked = cursor.getLong(0),
                    trackersBlocked = cursor.getLong(1),
                    popupsBlocked = cursor.getLong(2),
                    malwareBlocked = cursor.getLong(3),
                    maliciousBlocked = cursor.getLong(4),
                    phishingBlocked = cursor.getLong(5),
                    unwantedScriptsBlocked = cursor.getLong(6),
                    thirdPartyBlocked = cursor.getLong(7),
                    totalBlocked = cursor.getLong(8),
                    dataSavedBytes = cursor.getLong(9),
                    timeSavedMs = cursor.getLong(10),
                    lastUpdated = cursor.getLong(11)
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
                "POPUPS" -> "popups_blocked"
                "MALWARE" -> "malware_blocked"
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
            incrementDailyStats(category, bytesSaved, timeSavedMs)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun getBlockedTodayStats(): BlockedTodayStats {
        val todayKey = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
        try {
            val db = readableDatabase
            val cursor = db.rawQuery(
                "SELECT ads_blocked, trackers_blocked, popups_blocked, malware_blocked, data_saved_bytes, time_saved_ms, tokens_cleaned, bounces_stopped FROM privacy_shield_daily_stats WHERE date_key = ?",
                arrayOf(todayKey)
            )
            if (cursor.moveToFirst()) {
                val stats = BlockedTodayStats(
                    ads = cursor.getLong(0),
                    trackers = cursor.getLong(1),
                    popups = cursor.getLong(2),
                    malware = cursor.getLong(3),
                    dataSavedBytes = cursor.getLong(4),
                    timeSavedMs = cursor.getLong(5),
                    tokensCleaned = if (cursor.columnCount > 6) cursor.getLong(6) else 0L,
                    bouncesStopped = if (cursor.columnCount > 7) cursor.getLong(7) else 0L
                )
                cursor.close()
                if (stats.ads == 1284L && stats.trackers == 392L) {
                    try {
                        val writeDb = writableDatabase
                        writeDb.execSQL("UPDATE privacy_shield_daily_stats SET ads_blocked = 0, trackers_blocked = 0, popups_blocked = 0, malware_blocked = 0, data_saved_bytes = 0, time_saved_ms = 0 WHERE date_key = ?", arrayOf(todayKey))
                    } catch (_: Exception) {}
                    return BlockedTodayStats(ads = 0L, trackers = 0L, popups = 0L, malware = 0L, tokensCleaned = 0L, bouncesStopped = 0L, dataSavedBytes = 0L, timeSavedMs = 0L)
                }
                return stats
            }
            cursor.close()

            // Initialize clean baseline for the day: 0
            val writeDb = writableDatabase
            val initialAds = 0L
            val initialTrackers = 0L
            val initialPopups = 0L
            val initialMalware = 0L
            val initialTokens = 0L
            val initialBounces = 0L
            val initialData = 0L
            val initialTime = 0L
            val cv = ContentValues().apply {
                put("date_key", todayKey)
                put("ads_blocked", initialAds)
                put("trackers_blocked", initialTrackers)
                put("popups_blocked", initialPopups)
                put("malware_blocked", initialMalware)
                put("tokens_cleaned", initialTokens)
                put("bounces_stopped", initialBounces)
                put("data_saved_bytes", initialData)
                put("time_saved_ms", initialTime)
            }
            writeDb.insertWithOnConflict("privacy_shield_daily_stats", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
            return BlockedTodayStats(
                ads = initialAds,
                trackers = initialTrackers,
                popups = initialPopups,
                malware = initialMalware,
                tokensCleaned = initialTokens,
                bouncesStopped = initialBounces,
                dataSavedBytes = initialData,
                timeSavedMs = initialTime
            )
        } catch (e: Exception) {
            e.printStackTrace()
            return BlockedTodayStats(ads = 0L, trackers = 0L, popups = 0L, malware = 0L)
        }
    }

    fun incrementDailyStats(category: String, bytesSaved: Long, timeSavedMs: Long) {
        val todayKey = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
        val column = when (category.uppercase()) {
            "ADS" -> "ads_blocked"
            "TRACKERS", "UNWANTED_SCRIPTS", "THIRD_PARTY" -> "trackers_blocked"
            "POPUPS" -> "popups_blocked"
            "MALWARE", "MALICIOUS", "PHISHING" -> "malware_blocked"
            "TOKENS_CLEANED" -> "tokens_cleaned"
            "BOUNCES_STOPPED" -> "bounces_stopped"
            else -> "ads_blocked"
        }
        try {
            val db = writableDatabase
            getBlockedTodayStats() // Ensure today row initialized
            val sql = "UPDATE privacy_shield_daily_stats SET $column = $column + 1, data_saved_bytes = data_saved_bytes + ?, time_saved_ms = time_saved_ms + ? WHERE date_key = ?"
            db.execSQL(sql, arrayOf(bytesSaved, timeSavedMs, todayKey))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun incrementTokensCleaned(count: Long = 1) {
        val todayKey = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
        try {
            val db = writableDatabase
            getBlockedTodayStats()
            db.execSQL("UPDATE privacy_shield_daily_stats SET tokens_cleaned = tokens_cleaned + ? WHERE date_key = ?", arrayOf(count, todayKey))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun incrementBouncesStopped(count: Long = 1) {
        val todayKey = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
        try {
            val db = writableDatabase
            getBlockedTodayStats()
            db.execSQL("UPDATE privacy_shield_daily_stats SET bounces_stopped = bounces_stopped + ? WHERE date_key = ?", arrayOf(count, todayKey))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun getUserCustomRules(): List<UserCustomRule> {
        val list = mutableListOf<UserCustomRule>()
        try {
            val cursor = readableDatabase.rawQuery(
                "SELECT id, rule_text, is_cosmetic, is_exception, is_enabled, created_at FROM user_custom_rules ORDER BY id DESC",
                null
            )
            while (cursor.moveToNext()) {
                list.add(
                    UserCustomRule(
                        id = cursor.getLong(0),
                        ruleText = cursor.getString(1),
                        isCosmetic = cursor.getInt(2) == 1,
                        isException = cursor.getInt(3) == 1,
                        isEnabled = cursor.getInt(4) == 1,
                        createdAt = cursor.getLong(5)
                    )
                )
            }
            cursor.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    fun addUserCustomRule(ruleText: String): Boolean {
        val text = ruleText.trim()
        if (text.isEmpty()) return false
        val isCosmetic = text.contains("##") || text.contains("#@#")
        val isException = text.startsWith("@@") || text.contains("#@#")
        return try {
            val db = writableDatabase
            val cv = ContentValues().apply {
                put("rule_text", text)
                put("is_cosmetic", if (isCosmetic) 1 else 0)
                put("is_exception", if (isException) 1 else 0)
                put("is_enabled", 1)
                put("created_at", System.currentTimeMillis())
            }
            db.insertWithOnConflict("user_custom_rules", null, cv, SQLiteDatabase.CONFLICT_REPLACE) > 0
        } catch (e: Exception) {
            false
        }
    }

    fun deleteUserCustomRule(id: Long): Boolean {
        return try {
            writableDatabase.delete("user_custom_rules", "id = ?", arrayOf(id.toString())) > 0
        } catch (e: Exception) {
            false
        }
    }

    fun toggleUserCustomRule(id: Long, enabled: Boolean): Boolean {
        return try {
            val cv = ContentValues().apply { put("is_enabled", if (enabled) 1 else 0) }
            writableDatabase.update("user_custom_rules", cv, "id = ?", arrayOf(id.toString())) > 0
        } catch (e: Exception) {
            false
        }
    }

    fun getFilterSubscriptions(): List<FilterSubscription> {
        val list = mutableListOf<FilterSubscription>()
        try {
            val cursor = readableDatabase.rawQuery(
                "SELECT id, name, description, url, is_regional, is_custom, is_enabled, rule_count, last_updated FROM custom_filter_subscriptions ORDER BY is_custom ASC, name ASC",
                null
            )
            while (cursor.moveToNext()) {
                list.add(
                    FilterSubscription(
                        id = cursor.getString(0),
                        name = cursor.getString(1),
                        description = cursor.getString(2) ?: "",
                        url = cursor.getString(3),
                        isRegional = cursor.getInt(4) == 1,
                        isCustom = cursor.getInt(5) == 1,
                        isEnabled = cursor.getInt(6) == 1,
                        ruleCount = cursor.getInt(7),
                        lastUpdated = cursor.getLong(8)
                    )
                )
            }
            cursor.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    fun addCustomSubscription(name: String, url: String): Boolean {
        val trimmedUrl = url.trim()
        if (trimmedUrl.isEmpty()) return false
        val id = "custom_" + Math.abs(trimmedUrl.hashCode()).toString()
        val displayName = if (name.isBlank()) "Custom Filter List" else name.trim()
        return try {
            val cv = ContentValues().apply {
                put("id", id)
                put("name", displayName)
                put("description", "User custom filter list")
                put("url", trimmedUrl)
                put("is_regional", 0)
                put("is_custom", 1)
                put("is_enabled", 1)
                put("rule_count", 0)
                put("last_updated", 0L)
            }
            writableDatabase.insertWithOnConflict("custom_filter_subscriptions", null, cv, SQLiteDatabase.CONFLICT_REPLACE) > 0
        } catch (e: Exception) {
            false
        }
    }

    fun deleteCustomSubscription(id: String): Boolean {
        return try {
            writableDatabase.delete("custom_filter_subscriptions", "id = ?", arrayOf(id)) > 0
        } catch (e: Exception) {
            false
        }
    }

    fun setSubscriptionEnabled(id: String, enabled: Boolean): Boolean {
        return try {
            val cv = ContentValues().apply { put("is_enabled", if (enabled) 1 else 0) }
            writableDatabase.update("custom_filter_subscriptions", cv, "id = ?", arrayOf(id)) > 0
        } catch (e: Exception) {
            false
        }
    }

    fun updateSubscriptionStats(id: String, ruleCount: Int, lastUpdated: Long) {
        try {
            val cv = ContentValues().apply {
                put("rule_count", ruleCount)
                put("last_updated", lastUpdated)
            }
            writableDatabase.update("custom_filter_subscriptions", cv, "id = ?", arrayOf(id))
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
            val todayKey = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
            db.execSQL("UPDATE privacy_shield_stats SET ads_blocked = 0, trackers_blocked = 0, popups_blocked = 0, malware_blocked = 0, malicious_blocked = 0, phishing_blocked = 0, unwanted_scripts_blocked = 0, third_party_blocked = 0, total_blocked = 0, data_saved_bytes = 0, time_saved_ms = 0, last_updated = ? WHERE id = 1", arrayOf(System.currentTimeMillis()))
            db.delete("privacy_shield_events", null, null)
            db.execSQL("UPDATE privacy_shield_daily_stats SET ads_blocked = 0, trackers_blocked = 0, popups_blocked = 0, malware_blocked = 0, tokens_cleaned = 0, bounces_stopped = 0, data_saved_bytes = 0, time_saved_ms = 0 WHERE date_key = ?", arrayOf(todayKey))
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
            "POPUPS" -> "POPUPS"
            "MALWARE", "MALICIOUS", "PHISHING" -> "MALWARE"
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
                points[i] = 0.0f
            }
        } else {
            for (i in 0 until numBuckets) {
                points[i] = if (counts[i] == 0L) 0.0f else (counts[i].toFloat() / maxVal.toFloat()).coerceIn(0.08f, 0.95f)
            }
        }

        return Pair(points, labels)
    }

    // ==========================================
    // OFFLINE SAVED PAGES
    // ==========================================

    fun saveOfflinePage(title: String, url: String, content: String): Long {
        val db = writableDatabase
        val values = ContentValues().apply {
            put("title", title.ifBlank { "Saved Page" })
            put("url", url)
            put("content", content)
            put("saved_at", System.currentTimeMillis())
        }
        return db.insertWithOnConflict("offline_pages", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun getOfflinePages(): List<OfflinePageItem> {
        val list = mutableListOf<OfflinePageItem>()
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT id, title, url, content, saved_at FROM offline_pages ORDER BY saved_at DESC", null)
        while (cursor.moveToNext()) {
            list.add(
                OfflinePageItem(
                    id = cursor.getLong(0),
                    title = cursor.getString(1),
                    url = cursor.getString(2),
                    content = cursor.getString(3),
                    savedAt = cursor.getLong(4)
                )
            )
        }
        cursor.close()
        return list
    }

    fun getOfflinePage(url: String): OfflinePageItem? {
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT id, title, url, content, saved_at FROM offline_pages WHERE url = ?", arrayOf(url))
        val item = if (cursor.moveToFirst()) {
            OfflinePageItem(
                id = cursor.getLong(0),
                title = cursor.getString(1),
                url = cursor.getString(2),
                content = cursor.getString(3),
                savedAt = cursor.getLong(4)
            )
        } else null
        cursor.close()
        return item
    }

    fun deleteOfflinePage(id: Long): Int {
        return writableDatabase.delete("offline_pages", "id = ?", arrayOf(id.toString()))
    }

    fun clearAllOfflinePages(): Int {
        return writableDatabase.delete("offline_pages", null, null)
    }

    // =========================================================================
    // SAVED SELECTIONS / WEB SNIPPETS
    // =========================================================================
    fun addSavedSelection(title: String, url: String, selectedText: String, note: String = ""): Long {
        val values = ContentValues().apply {
            put("title", title)
            put("url", url)
            put("selected_text", selectedText)
            put("note", note)
            put("timestamp", System.currentTimeMillis())
        }
        return writableDatabase.insert("saved_selections", null, values)
    }

    fun getAllSavedSelections(): List<SavedSelectionItem> {
        val list = mutableListOf<SavedSelectionItem>()
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT id, title, url, selected_text, note, timestamp FROM saved_selections ORDER BY timestamp DESC", null)
        while (cursor.moveToNext()) {
            list.add(
                SavedSelectionItem(
                    id = cursor.getLong(0),
                    title = cursor.getString(1),
                    url = cursor.getString(2),
                    selectedText = cursor.getString(3),
                    note = cursor.getString(4),
                    timestamp = cursor.getLong(5)
                )
            )
        }
        cursor.close()
        return list
    }

    fun deleteSavedSelection(id: Long): Int {
        return writableDatabase.delete("saved_selections", "id = ?", arrayOf(id.toString()))
    }

    fun clearAllSavedSelections(): Int {
        return writableDatabase.delete("saved_selections", null, null)
    }
}

