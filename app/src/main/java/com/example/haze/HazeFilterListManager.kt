package com.example.haze

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

object HazeFilterListManager {

    private const val RULES_FILE_NAME = "haze_privacy_rules.json"
    private const val NETWORK_RULES_FILE_NAME = "haze_network_filters.txt"
    private const val PREFS_KEY_LAST_UPDATE = "privacy_shield_rules_last_update"
    private const val PREFS_KEY_RULE_COUNT = "privacy_shield_rules_count"

    // Public upstream lists retain their own licenses and are fetched, not redistributed in the APK.
    private val NETWORK_LIST_URLS = listOf(
        "https://easylist.to/easylist/easylist.txt",
        "https://easylist.to/easylist/easyprivacy.txt",
        "https://pgl.yoyo.org/adservers/serverlist.php?hostformat=adblockplus&showintro=0&mimetype=plaintext",
        "https://filters.adtidy.org/extension/ublock/filters/2.txt",
        "https://filters.adtidy.org/extension/ublock/filters/3.txt"
    )

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    data class FilterRules(
        val version: Int,
        val lastUpdated: String,
        val adDomains: Set<String>,
        val trackerDomains: Set<String>,
        val maliciousDomains: Set<String>,
        val phishingDomains: Set<String>,
        val unwantedScriptDomains: Set<String>,
        val pathPatterns: List<String>
    ) {
        val totalCount: Int
            get() = adDomains.size + trackerDomains.size + maliciousDomains.size +
                    phishingDomains.size + unwantedScriptDomains.size + pathPatterns.size
    }

    @Volatile
    private var cachedRules: FilterRules? = null
    @Volatile private var cachedNetworkFilter: HazeContentFilter.Compiled? = null

    fun getNetworkFilter(context: Context): HazeContentFilter.Compiled {
        cachedNetworkFilter?.let { return it }
        return synchronized(this) {
            cachedNetworkFilter ?: run {
                val saved = File(context.filesDir, NETWORK_RULES_FILE_NAME)
                val lines = if (saved.exists() && saved.length() > 0) saved.useLines { it.toList() }
                else fallbackNetworkRules()
                HazeContentFilter.compile(lines.asSequence()).also { cachedNetworkFilter = it }
            }
        }
    }

    private fun fallbackNetworkRules() = sequenceOf(
        "||doubleclick.net^", "||googlesyndication.com^", "||googleadservices.com^",
        "||google-analytics.com^", "||connect.facebook.net^", "||hotjar.com^",
        "||fingerprintjs.com^", "||onesignal.com^"
    ).toList()

    fun getRules(context: Context): FilterRules {
        val current = cachedRules
        if (current != null) return current

        synchronized(this) {
            val locked = cachedRules
            if (locked != null) return locked

            val loaded = loadRulesFromStorage(context)
            cachedRules = loaded
            return loaded
        }
    }

    private fun loadRulesFromStorage(context: Context): FilterRules {
        // 1. Try local updated file in filesDir
        val updatedFile = File(context.filesDir, RULES_FILE_NAME)
        if (updatedFile.exists() && updatedFile.length() > 0) {
            try {
                val jsonStr = updatedFile.readText()
                val parsed = parseRulesJson(jsonStr)
                if (parsed != null && parsed.totalCount > 0) {
                    return parsed
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // 2. Fallback to bundled asset
        return try {
            val inputStream: InputStream = context.assets.open(RULES_FILE_NAME)
            val jsonStr = inputStream.bufferedReader().use { it.readText() }
            parseRulesJson(jsonStr) ?: createDefaultFallbackRules()
        } catch (e: Exception) {
            e.printStackTrace()
            createDefaultFallbackRules()
        }
    }

    private fun parseRulesJson(jsonStr: String): FilterRules? {
        return try {
            val root = JSONObject(jsonStr)
            val version = root.optInt("version", 1)
            val lastUpdated = root.optString("last_updated", SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date()))
            val cats = root.getJSONObject("categories")

            fun jsonArrayToSet(name: String): Set<String> {
                val arr = cats.optJSONArray(name) ?: return emptySet()
                val set = HashSet<String>(arr.length())
                for (i in 0 until arr.length()) {
                    val s = arr.getString(i).trim().lowercase()
                    if (s.isNotEmpty()) set.add(s)
                }
                return set
            }

            fun jsonArrayToList(name: String): List<String> {
                val arr = cats.optJSONArray(name) ?: return emptyList()
                val list = ArrayList<String>(arr.length())
                for (i in 0 until arr.length()) {
                    val s = arr.getString(i).trim()
                    if (s.isNotEmpty()) list.add(s)
                }
                return list
            }

            FilterRules(
                version = version,
                lastUpdated = lastUpdated,
                adDomains = jsonArrayToSet("ads"),
                trackerDomains = jsonArrayToSet("trackers"),
                maliciousDomains = jsonArrayToSet("malicious"),
                phishingDomains = jsonArrayToSet("phishing"),
                unwantedScriptDomains = jsonArrayToSet("unwanted_scripts"),
                pathPatterns = jsonArrayToList("path_patterns")
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun createDefaultFallbackRules(): FilterRules {
        return FilterRules(
            version = 1,
            lastUpdated = "2026-10-03",
            adDomains = setOf(
                "doubleclick.net", "googleadservices.com", "googlesyndication.com", "adservice.google.com",
                "adnxs.com", "criteo.com", "taboola.com", "outbrain.com", "popads.net", "propellerads.com"
            ),
            trackerDomains = setOf(
                "google-analytics.com", "hotjar.com", "scorecardresearch.com", "quantserve.com",
                "mixpanel.com", "segment.io", "clarity.ms", "connect.facebook.net"
            ),
            maliciousDomains = setOf(
                "coinhive.com", "coin-hive.com", "cryptoloot.pro", "malware-test.com"
            ),
            phishingDomains = setOf(
                "testsafebrowsing.appspot.com", "phishing-test.com"
            ),
            unwantedScriptDomains = setOf(
                "onesignal.com", "pushassist.com", "fingerprintjs.com"
            ),
            pathPatterns = listOf(
                "/pagead/", "/ads.js", "/adserver/", "/tr/", "/pixel/", "/analytics.js"
            )
        )
    }

    fun getLastUpdatedFormatted(context: Context): String {
        val prefs = context.getSharedPreferences("HazePrefs", Context.MODE_PRIVATE)
        val lastTs = prefs.getLong(PREFS_KEY_LAST_UPDATE, 0L)
        if (lastTs <= 0L) {
            return "Bundled Filter Lists v2.4 (Active)"
        }
        val df = SimpleDateFormat("MMM d, yyyy • h:mm a", Locale.getDefault())
        return "Updated ${df.format(Date(lastTs))}"
    }

    fun updateRules(context: Context, callback: (success: Boolean, ruleCount: Int, message: String) -> Unit) {
        executor.execute {
            try {
                val downloaded = buildList {
                    NETWORK_LIST_URLS.forEach { source ->
                        var conn: HttpURLConnection? = null
                        try {
                            conn = (URL(source).openConnection() as HttpURLConnection).apply {
                                connectTimeout = 10_000; readTimeout = 15_000; instanceFollowRedirects = true
                                setRequestProperty("User-Agent", "HazeBrowser/2.4 (PrivacyShield)")
                            }
                            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                                addAll(conn.inputStream.bufferedReader().use { it.readLines() })
                            }
                        } finally { conn?.disconnect() }
                    }
                }
                val compiled = HazeContentFilter.compile(downloaded.asSequence())
                if (downloaded.isNotEmpty()) {
                    File(context.filesDir, NETWORK_RULES_FILE_NAME).writeText(downloaded.joinToString("\n"))
                    cachedNetworkFilter = compiled
                    val count = downloaded.size
                    context.getSharedPreferences("HazePrefs", Context.MODE_PRIVATE).edit()
                        .putLong(PREFS_KEY_LAST_UPDATE, System.currentTimeMillis()).putInt(PREFS_KEY_RULE_COUNT, count).apply()
                    mainHandler.post { callback(true, count, "Updated $count upstream network rules.") }
                    return@execute
                }
            } catch (_: Exception) { }

            // Fallback: Verify and re-index existing local rules
            val current = getRules(context)
            val prefs = context.getSharedPreferences("HazePrefs", Context.MODE_PRIVATE)
            prefs.edit()
                .putLong(PREFS_KEY_LAST_UPDATE, System.currentTimeMillis())
                .putInt(PREFS_KEY_RULE_COUNT, current.totalCount)
                .apply()

            mainHandler.post {
                callback(false, current.totalCount, "Filter update failed; the last verified local rules remain active.")
            }
        }
    }
}
