package com.example.haze

import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.util.Patterns
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

import android.text.Editable
import android.text.TextWatcher
import android.widget.HorizontalScrollView
import android.widget.PopupWindow
import android.widget.ScrollView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog

/**
 * HazeOmniboxManager
 * Manages Omnibox features:
 * - Smart Clipboard Detection (URLs, phone numbers, search queries)
 * - Search Destinations Catalog (Essential, Video, Social, Shopping, AI, Knowledge, Travel, Productivity)
 * - Compact Search Engine Selector Strip with Pinning & Personalization
 * - Search Shortcuts & Prefix routing (g, yt, gh, w, b, ddg, br, r, a, gpt, gem, etc.)
 * - Custom Search Engines (creation & persistence)
 * - QR / Barcode Scanner integration
 */
class HazeOmniboxManager(
    private val activity: MainActivity,
    private val prefs: SharedPreferences
) {
    companion object {
        val DEFAULT_PINNED_IDS = listOf("google", "bing", "duckduckgo", "youtube", "chatgpt")
        var activeDropdownWindow: PopupWindow? = null
    }

    data class SearchEngine(
        val id: String,
        val name: String,
        val prefix: String,
        val searchUrlPattern: String,
        val iconRes: Int,
        val isCustom: Boolean = false,
        val category: String = "Essential",
        val description: String = ""
    )

    sealed class ClipboardItem {
        data class UrlItem(val url: String) : ClipboardItem()
        data class PhoneItem(val number: String) : ClipboardItem()
        data class QueryItem(val query: String) : ClipboardItem()
    }

    private var lastDismissedClip: String? = null

    // Comprehensive catalog of built-in search engines and destinations
    val defaultEngines = listOf(
        // 1. Essential
        SearchEngine(
            id = "google",
            name = "Google",
            prefix = "g",
            searchUrlPattern = "https://www.google.com/search?q=%s",
            iconRes = R.drawable.ic_google_logo,
            category = "Essential",
            description = "General web search, images, news, maps"
        ),
        SearchEngine(
            id = "bing",
            name = "Bing",
            prefix = "b",
            searchUrlPattern = "https://www.bing.com/search?q=%s",
            iconRes = R.drawable.ic_bing,
            category = "Essential",
            description = "Web search and AI-powered search"
        ),
        SearchEngine(
            id = "duckduckgo",
            name = "DuckDuckGo",
            prefix = "ddg",
            searchUrlPattern = "https://duckduckgo.com/?q=%s",
            iconRes = R.drawable.ic_duckduckgo,
            category = "Essential",
            description = "Privacy-focused search"
        ),
        SearchEngine(
            id = "brave",
            name = "Brave Search",
            prefix = "br",
            searchUrlPattern = "https://search.brave.com/search?q=%s",
            iconRes = R.drawable.ic_brave,
            category = "Essential",
            description = "Independent search index"
        ),
        SearchEngine(
            id = "perplexity",
            name = "Perplexity",
            prefix = "px",
            searchUrlPattern = "https://www.perplexity.ai/search?q=%s",
            iconRes = R.drawable.ic_perplexity,
            category = "Essential",
            description = "AI answers with sources"
        ),
        SearchEngine(
            id = "yahoo",
            name = "Yahoo Search",
            prefix = "y",
            searchUrlPattern = "https://search.yahoo.com/search?p=%s",
            iconRes = R.drawable.ic_yahoo,
            category = "Essential",
            description = "Web search and news"
        ),
        SearchEngine(
            id = "ecosia",
            name = "Ecosia",
            prefix = "eco",
            searchUrlPattern = "https://www.ecosia.org/search?q=%s",
            iconRes = R.drawable.ic_ecosia,
            category = "Essential",
            description = "Search with an environmental mission"
        ),

        // 2. Video & Streaming
        SearchEngine(
            id = "youtube",
            name = "YouTube",
            prefix = "yt",
            searchUrlPattern = "https://m.youtube.com/results?search_query=%s",
            iconRes = R.drawable.ic_youtube,
            category = "Video & Streaming",
            description = "Watch videos, music and livestreams"
        ),
        SearchEngine(
            id = "tiktok",
            name = "TikTok",
            prefix = "tt",
            searchUrlPattern = "https://www.tiktok.com/search?q=%s",
            iconRes = R.drawable.ic_tiktok,
            category = "Video & Streaming",
            description = "Short-form videos and creators"
        ),
        SearchEngine(
            id = "twitch",
            name = "Twitch",
            prefix = "tw",
            searchUrlPattern = "https://www.twitch.tv/search?term=%s",
            iconRes = R.drawable.ic_twitch,
            category = "Video & Streaming",
            description = "Live game streams and creators"
        ),
        SearchEngine(
            id = "vimeo",
            name = "Vimeo",
            prefix = "vm",
            searchUrlPattern = "https://vimeo.com/search?q=%s",
            iconRes = R.drawable.ic_vimeo,
            category = "Video & Streaming",
            description = "High quality videos and filmmakers"
        ),
        SearchEngine(
            id = "netflix",
            name = "Netflix",
            prefix = "nf",
            searchUrlPattern = "https://www.netflix.com/search?q=%s",
            iconRes = R.drawable.ic_netflix,
            category = "Video & Streaming",
            description = "Movies and TV series catalog"
        ),
        SearchEngine(
            id = "spotify",
            name = "Spotify",
            prefix = "sp",
            searchUrlPattern = "https://open.spotify.com/search/%s",
            iconRes = R.drawable.ic_spotify,
            category = "Video & Streaming",
            description = "Music, podcasts and artists"
        ),

        // 3. Social & Community
        SearchEngine(
            id = "reddit",
            name = "Reddit",
            prefix = "r",
            searchUrlPattern = "https://www.reddit.com/search/?q=%s",
            iconRes = R.drawable.ic_reddit,
            category = "Social & Community",
            description = "Discussions, forums and communities"
        ),
        SearchEngine(
            id = "instagram",
            name = "Instagram",
            prefix = "ig",
            searchUrlPattern = "https://www.instagram.com/explore/tags/%s/",
            iconRes = R.drawable.ic_instagram,
            category = "Social & Community",
            description = "Photos, reels and creator profiles"
        ),
        SearchEngine(
            id = "facebook",
            name = "Facebook",
            prefix = "fb",
            searchUrlPattern = "https://www.facebook.com/public/%s",
            iconRes = R.drawable.ic_facebook,
            category = "Social & Community",
            description = "Social posts, friends and pages"
        ),
        SearchEngine(
            id = "x",
            name = "X",
            prefix = "x",
            searchUrlPattern = "https://x.com/search?q=%s",
            iconRes = R.drawable.ic_x,
            category = "Social & Community",
            description = "Real-time news, posts and trends"
        ),
        SearchEngine(
            id = "threads",
            name = "Threads",
            prefix = "th",
            searchUrlPattern = "https://www.threads.net/search?q=%s",
            iconRes = R.drawable.ic_threads,
            category = "Social & Community",
            description = "Discussions and sharing community"
        ),
        SearchEngine(
            id = "pinterest",
            name = "Pinterest",
            prefix = "pin",
            searchUrlPattern = "https://www.pinterest.com/search/pins/?q=%s",
            iconRes = R.drawable.ic_pinterest,
            category = "Social & Community",
            description = "Visual discovery and creative ideas"
        ),
        SearchEngine(
            id = "linkedin",
            name = "LinkedIn",
            prefix = "in",
            searchUrlPattern = "https://www.linkedin.com/search/results/all/?keywords=%s",
            iconRes = R.drawable.ic_linkedin,
            category = "Social & Community",
            description = "Professional networking and jobs"
        ),
        SearchEngine(
            id = "quora",
            name = "Quora",
            prefix = "qu",
            searchUrlPattern = "https://www.quora.com/search?q=%s",
            iconRes = R.drawable.ic_search,
            category = "Social & Community",
            description = "Q&A knowledge community"
        ),

        // 4. Shopping
        SearchEngine(
            id = "amazon",
            name = "Amazon",
            prefix = "a",
            searchUrlPattern = "https://www.amazon.com/s?k=%s",
            iconRes = R.drawable.ic_amazon,
            category = "Shopping",
            description = "Online shopping, deals and products"
        ),
        SearchEngine(
            id = "flipkart",
            name = "Flipkart",
            prefix = "fk",
            searchUrlPattern = "https://www.flipkart.com/search?q=%s",
            iconRes = R.drawable.ic_shopping,
            category = "Shopping",
            description = "Online shopping and electronics in India"
        ),
        SearchEngine(
            id = "ebay",
            name = "eBay",
            prefix = "eb",
            searchUrlPattern = "https://www.ebay.com/sch/i.html?_nkw=%s",
            iconRes = R.drawable.ic_shopping,
            category = "Shopping",
            description = "Global auctions and marketplace"
        ),
        SearchEngine(
            id = "etsy",
            name = "Etsy",
            prefix = "et",
            searchUrlPattern = "https://www.etsy.com/search?q=%s",
            iconRes = R.drawable.ic_shopping,
            category = "Shopping",
            description = "Handmade, vintage and craft items"
        ),
        SearchEngine(
            id = "aliexpress",
            name = "AliExpress",
            prefix = "ali",
            searchUrlPattern = "https://www.aliexpress.com/wholesale?SearchText=%s",
            iconRes = R.drawable.ic_shopping,
            category = "Shopping",
            description = "Wholesale goods and factory bargains"
        ),
        SearchEngine(
            id = "myntra",
            name = "Myntra",
            prefix = "my",
            searchUrlPattern = "https://www.myntra.com/%s",
            iconRes = R.drawable.ic_shopping,
            category = "Shopping",
            description = "Fashion, footwear and lifestyle apparel"
        ),
        SearchEngine(
            id = "temu",
            name = "Temu",
            prefix = "te",
            searchUrlPattern = "https://www.temu.com/search_result.html?search_key=%s",
            iconRes = R.drawable.ic_shopping,
            category = "Shopping",
            description = "Affordable global shopping deals"
        ),

        // 5. AI Search & Assistants
        SearchEngine(
            id = "chatgpt",
            name = "ChatGPT",
            prefix = "gpt",
            searchUrlPattern = "https://chatgpt.com/?q=%s",
            iconRes = R.drawable.ic_chatgpt,
            category = "AI Search & Assistants",
            description = "Conversational AI assistant by OpenAI"
        ),
        SearchEngine(
            id = "gemini",
            name = "Gemini",
            prefix = "gem",
            searchUrlPattern = "https://gemini.google.com/app?q=%s",
            iconRes = R.drawable.ic_sparkles,
            category = "AI Search & Assistants",
            description = "Google's conversational AI assistant"
        ),
        SearchEngine(
            id = "claude",
            name = "Claude",
            prefix = "cl",
            searchUrlPattern = "https://claude.ai/new?q=%s",
            iconRes = R.drawable.ic_sparkles,
            category = "AI Search & Assistants",
            description = "Helpful and safe AI assistant by Anthropic"
        ),
        SearchEngine(
            id = "copilot",
            name = "Microsoft Copilot",
            prefix = "cop",
            searchUrlPattern = "https://copilot.microsoft.com/?q=%s",
            iconRes = R.drawable.ic_sparkle,
            category = "AI Search & Assistants",
            description = "AI companion powered by Microsoft"
        ),
        SearchEngine(
            id = "deepseek",
            name = "DeepSeek",
            prefix = "ds",
            searchUrlPattern = "https://chat.deepseek.com/?q=%s",
            iconRes = R.drawable.ic_sparkles,
            category = "AI Search & Assistants",
            description = "Advanced reasoning and coding AI"
        ),

        // 6. Knowledge & Technical Search
        SearchEngine(
            id = "wikipedia",
            name = "Wikipedia (en)",
            prefix = "w",
            searchUrlPattern = "https://en.wikipedia.org/wiki/Special:Search?search=%s",
            iconRes = R.drawable.ic_wikipedia,
            category = "Knowledge & Technical Search",
            description = "The free encyclopedia"
        ),
        SearchEngine(
            id = "stackoverflow",
            name = "Stack Overflow",
            prefix = "so",
            searchUrlPattern = "https://stackoverflow.com/search?q=%s",
            iconRes = R.drawable.ic_code,
            category = "Knowledge & Technical Search",
            description = "Developer Q&A and coding problems"
        ),
        SearchEngine(
            id = "github",
            name = "GitHub",
            prefix = "gh",
            searchUrlPattern = "https://github.com/search?q=%s",
            iconRes = R.drawable.ic_github,
            category = "Knowledge & Technical Search",
            description = "Code repositories and developers"
        ),
        SearchEngine(
            id = "mdn",
            name = "MDN Web Docs",
            prefix = "mdn",
            searchUrlPattern = "https://developer.mozilla.org/en-US/search?q=%s",
            iconRes = R.drawable.ic_code,
            category = "Knowledge & Technical Search",
            description = "Web standards, HTML, CSS & JavaScript"
        ),
        SearchEngine(
            id = "geeksforgeeks",
            name = "GeeksforGeeks",
            prefix = "gfg",
            searchUrlPattern = "https://www.geeksforgeeks.org/search/?q=%s",
            iconRes = R.drawable.ic_code,
            category = "Knowledge & Technical Search",
            description = "Computer science portal & algorithms"
        ),
        SearchEngine(
            id = "w3schools",
            name = "W3Schools",
            prefix = "w3",
            searchUrlPattern = "https://www.w3schools.com/howto/search.asp?search=%s",
            iconRes = R.drawable.ic_code,
            category = "Knowledge & Technical Search",
            description = "Web developer tutorials & references"
        ),

        // 7. Travel, Maps & Bookings
        SearchEngine(
            id = "google_maps",
            name = "Google Maps",
            prefix = "map",
            searchUrlPattern = "https://www.google.com/maps/search/%s",
            iconRes = R.drawable.ic_map,
            category = "Travel, Maps & Bookings",
            description = "Turn-by-turn navigation and places"
        ),
        SearchEngine(
            id = "booking",
            name = "Booking.com",
            prefix = "bk",
            searchUrlPattern = "https://www.booking.com/searchresults.html?ss=%s",
            iconRes = R.drawable.ic_map,
            category = "Travel, Maps & Bookings",
            description = "Hotels, flights and travel stays"
        ),
        SearchEngine(
            id = "makemytrip",
            name = "MakeMyTrip",
            prefix = "mmt",
            searchUrlPattern = "https://www.makemytrip.com/search?q=%s",
            iconRes = R.drawable.ic_map,
            category = "Travel, Maps & Bookings",
            description = "Flight, hotel and train tickets in India"
        ),
        SearchEngine(
            id = "tripadvisor",
            name = "Tripadvisor",
            prefix = "ta",
            searchUrlPattern = "https://www.tripadvisor.com/Search?q=%s",
            iconRes = R.drawable.ic_map,
            category = "Travel, Maps & Bookings",
            description = "Travel reviews, hotels and attractions"
        ),
        SearchEngine(
            id = "airbnb",
            name = "Airbnb",
            prefix = "ab",
            searchUrlPattern = "https://www.airbnb.com/s/%s/homes",
            iconRes = R.drawable.ic_map,
            category = "Travel, Maps & Bookings",
            description = "Vacation rentals and homestays"
        ),

        // 8. Email & Productivity
        SearchEngine(
            id = "gmail",
            name = "Gmail",
            prefix = "gm",
            searchUrlPattern = "https://mail.google.com/mail/u/0/#search/%s",
            iconRes = R.drawable.ic_mail,
            category = "Email & Productivity",
            description = "Fast, secure email search"
        ),
        SearchEngine(
            id = "outlook",
            name = "Outlook",
            prefix = "out",
            searchUrlPattern = "https://outlook.live.com/mail/0/inbox?search=%s",
            iconRes = R.drawable.ic_mail,
            category = "Email & Productivity",
            description = "Microsoft Outlook email and calendar"
        ),
        SearchEngine(
            id = "google_drive",
            name = "Google Drive",
            prefix = "drv",
            searchUrlPattern = "https://drive.google.com/drive/u/0/search?q=%s",
            iconRes = R.drawable.ic_folder,
            category = "Email & Productivity",
            description = "Cloud files and documents search"
        ),
        SearchEngine(
            id = "google_translate",
            name = "Google Translate",
            prefix = "tr",
            searchUrlPattern = "https://translate.google.com/?sl=auto&tl=en&text=%s&op=translate",
            iconRes = R.drawable.ic_translate,
            category = "Email & Productivity",
            description = "Translate text and languages"
        ),
        SearchEngine(
            id = "microsoft_365",
            name = "Microsoft 365",
            prefix = "m365",
            searchUrlPattern = "https://www.office.com/search?q=%s",
            iconRes = R.drawable.ic_file,
            category = "Email & Productivity",
            description = "Office documents, files and apps"
        )
    )

    // Load custom engines from SharedPreferences
    fun getCustomEngines(): List<SearchEngine> {
        val list = mutableListOf<SearchEngine>()
        val jsonStr = prefs.getString("custom_search_engines_json", null) ?: return list
        try {
            val array = JSONArray(jsonStr)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    SearchEngine(
                        id = obj.optString("id", "custom_${System.currentTimeMillis()}"),
                        name = obj.getString("name"),
                        prefix = obj.getString("prefix").lowercase(Locale.US),
                        searchUrlPattern = obj.getString("url"),
                        iconRes = R.drawable.ic_search,
                        isCustom = true,
                        category = obj.optString("category", "Custom"),
                        description = obj.optString("description", "Custom search engine")
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    fun getAllEngines(): List<SearchEngine> {
        return defaultEngines + getCustomEngines()
    }

    fun saveCustomEngine(
        name: String,
        prefix: String,
        urlPattern: String,
        category: String = "Custom",
        description: String = "Custom search destination"
    ): Boolean {
        val cleanPrefix = prefix.trim().lowercase(Locale.US)
        val cleanUrl = if (urlPattern.contains("%s")) urlPattern.trim() else "${urlPattern.trim()}%s"
        val customList = getCustomEngines().toMutableList()
        // Remove existing with same prefix if any
        customList.removeAll { it.prefix.equals(cleanPrefix, ignoreCase = true) }
        customList.add(
            SearchEngine(
                id = "custom_${System.currentTimeMillis()}",
                name = name.trim(),
                prefix = cleanPrefix,
                searchUrlPattern = cleanUrl,
                iconRes = R.drawable.ic_search,
                isCustom = true,
                category = category,
                description = description
            )
        )
        val array = JSONArray()
        for (item in customList) {
            val obj = JSONObject()
            obj.put("id", item.id)
            obj.put("name", item.name)
            obj.put("prefix", item.prefix)
            obj.put("url", item.searchUrlPattern)
            obj.put("category", item.category)
            obj.put("description", item.description)
            array.put(obj)
        }
        return prefs.edit().putString("custom_search_engines_json", array.toString()).commit()
    }

    // Pinned engines management
    fun getPinnedEngineIds(): List<String> {
        val saved = prefs.getString("pinned_search_engine_ids", null)
        if (saved != null) {
            val list = mutableListOf<String>()
            try {
                val arr = JSONArray(saved)
                for (i in 0 until arr.length()) {
                    list.add(arr.getString(i))
                }
                if (list.isNotEmpty()) return list
            } catch (_: Exception) {}
        }
        return DEFAULT_PINNED_IDS
    }

    fun setPinnedEngineIds(ids: List<String>) {
        val arr = JSONArray()
        for (id in ids) arr.put(id)
        prefs.edit().putString("pinned_search_engine_ids", arr.toString()).apply()
    }

    fun togglePinEngine(engineId: String): Boolean {
        val current = getPinnedEngineIds().toMutableList()
        val isPinnedNow = if (current.contains(engineId)) {
            current.remove(engineId)
            false
        } else {
            current.add(engineId)
            true
        }
        setPinnedEngineIds(current)
        return isPinnedNow
    }

    fun isEnginePinned(engineId: String): Boolean {
        return getPinnedEngineIds().contains(engineId)
    }

    // Personalization & Usage Stats
    fun recordEngineUsage(engineId: String) {
        try {
            val json = prefs.getString("search_engine_usage_stats", "{}") ?: "{}"
            val obj = JSONObject(json)
            val count = obj.optInt(engineId, 0)
            obj.put(engineId, count + 1)
            prefs.edit().putString("search_engine_usage_stats", obj.toString()).apply()
        } catch (_: Exception) {}
    }

    fun getEngineUsage(engineId: String): Int {
        return try {
            val json = prefs.getString("search_engine_usage_stats", "{}") ?: "{}"
            JSONObject(json).optInt(engineId, 0)
        } catch (_: Exception) { 0 }
    }

    /**
     * Computes the compact strip of search engines to display:
     * 1. Pinned engines in user order (default: Google, Bing, DuckDuckGo, YouTube, ChatGPT)
     * 2. Top 2 frequently used unpinned engines (personalization)
     */
    fun getStripEngines(): List<SearchEngine> {
        val all = getAllEngines()
        val allMap = all.associateBy { it.id }
        val pinnedIds = getPinnedEngineIds()
        val result = mutableListOf<SearchEngine>()

        for (id in pinnedIds) {
            allMap[id]?.let { result.add(it) }
        }

        // Add top 2 frequently used unpinned engines
        val unpinnedWithUsage = all.filter { !pinnedIds.contains(it.id) }
            .map { Pair(it, getEngineUsage(it.id)) }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(2)
            .map { it.first }

        result.addAll(unpinnedWithUsage)

        if (result.isEmpty()) {
            return defaultEngines.take(5)
        }
        return result
    }

    /**
     * Parse prefix shortcut e.g. "yt lo-fi music", "g cats", "gh chromium source", "w quantum mechanics"
     * Returns Pair(SearchEngine, query) or null if not a shortcut
     */
    fun parseSearchShortcut(rawInput: String): Pair<SearchEngine, String>? {
        val decoded = try { Uri.decode(rawInput) } catch (_: Exception) { rawInput }
        val trimmed = decoded.trim()
        val spaceIndex = trimmed.indexOf(' ')
        if (spaceIndex <= 0) return null

        val rawPrefix = trimmed.substring(0, spaceIndex).lowercase(Locale.US)
        val prefix = rawPrefix.removeSuffix(":")
        val remainder = trimmed.substring(spaceIndex + 1).trim()
        if (remainder.isEmpty()) return null

        val all = getAllEngines()
        for (engine in all) {
            if (engine.prefix.equals(prefix, ignoreCase = true)) {
                return Pair(engine, remainder)
            }
        }

        // Additional common aliases
        return when (prefix) {
            "y", "youtube" -> all.find { it.id == "youtube" }?.let { Pair(it, remainder) }
            "wiki", "wikipedia" -> all.find { it.id == "wikipedia" }?.let { Pair(it, remainder) }
            "d", "ddg", "duck" -> all.find { it.id == "duckduckgo" }?.let { Pair(it, remainder) }
            "g", "google" -> all.find { it.id == "google" }?.let { Pair(it, remainder) }
            "b", "bing" -> all.find { it.id == "bing" }?.let { Pair(it, remainder) }
            "br", "brave" -> all.find { it.id == "brave" }?.let { Pair(it, remainder) }
            "gh", "github" -> all.find { it.id == "github" }?.let { Pair(it, remainder) }
            "r", "reddit" -> all.find { it.id == "reddit" }?.let { Pair(it, remainder) }
            "a", "amazon" -> all.find { it.id == "amazon" }?.let { Pair(it, remainder) }
            "ai", "gpt", "chat" -> all.find { it.id == "chatgpt" }?.let { Pair(it, remainder) }
            "gem" -> all.find { it.id == "gemini" }?.let { Pair(it, remainder) }
            "claude" -> all.find { it.id == "claude" }?.let { Pair(it, remainder) }
            "cop" -> all.find { it.id == "copilot" }?.let { Pair(it, remainder) }
            "ds" -> all.find { it.id == "deepseek" }?.let { Pair(it, remainder) }
            "px" -> all.find { it.id == "perplexity" }?.let { Pair(it, remainder) }
            "map", "maps" -> all.find { it.id == "google_maps" }?.let { Pair(it, remainder) }
            "drive" -> all.find { it.id == "google_drive" }?.let { Pair(it, remainder) }
            "trans", "translate" -> all.find { it.id == "google_translate" }?.let { Pair(it, remainder) }
            "stack", "so" -> all.find { it.id == "stackoverflow" }?.let { Pair(it, remainder) }
            else -> null
        }
    }

    /**
     * Builds full search URL for an engine and query
     */
    fun buildSearchUrl(engine: SearchEngine, query: String): String {
        val encoded = Uri.encode(query)
        return if (engine.searchUrlPattern.contains("%s")) {
            engine.searchUrlPattern.replace("%s", encoded)
        } else {
            "${engine.searchUrlPattern}$encoded"
        }
    }

    /**
     * Smart Clipboard Detection
     */
    fun getSmartClipboardItem(): ClipboardItem? {
        val clipManager = activity.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
        if (!clipManager.hasPrimaryClip()) return null
        val clip = clipManager.primaryClip ?: return null
        if (clip.itemCount == 0) return null
        val text = clip.getItemAt(0)?.coerceToText(activity)?.toString()?.trim() ?: return null
        if (text.isEmpty() || text == lastDismissedClip) return null

        // 1. Check if URL
        val isLikelyUrl = text.startsWith("http://", ignoreCase = true) ||
                text.startsWith("https://", ignoreCase = true) ||
                Patterns.WEB_URL.matcher(text).matches() ||
                (text.contains(".") && !text.contains(" ") && text.length > 3 && !text.endsWith("."))

        if (isLikelyUrl) {
            val url = if (text.startsWith("http://") || text.startsWith("https://")) text else "https://$text"
            return ClipboardItem.UrlItem(url)
        }

        // 2. Check if phone number / digit string (e.g. "123456789" or "+1234567890")
        val cleanDigits = text.replace(" ", "").replace("-", "")
        val isPhone = cleanDigits.matches(Regex("^[+]?[0-9]{7,15}$"))
        if (isPhone) {
            return ClipboardItem.PhoneItem(text)
        }

        // 3. Otherwise general text query for search (under 160 characters)
        if (text.length in 2..160 && !text.contains("\n")) {
            return ClipboardItem.QueryItem(text)
        }

        return null
    }

    /**
     * Render Smart Clipboard Card into container
     */
    fun renderSmartClipboardCard(container: ViewGroup): View? {
        val clipItem = getSmartClipboardItem() ?: return null
        val view = LayoutInflater.from(activity).inflate(R.layout.item_smart_clipboard, container, false)

        val card = view.findViewById<View>(R.id.cardSmartClipboard)
        val tvBadge = view.findViewById<TextView>(R.id.tvClipboardBadge)
        val ivBadgeIcon = view.findViewById<ImageView>(R.id.ivClipboardBadgeIcon)
        val tvAction = view.findViewById<TextView>(R.id.tvClipboardAction)
        val tvContent = view.findViewById<TextView>(R.id.tvClipboardContent)
        val ivType = view.findViewById<ImageView>(R.id.ivClipboardTypeIcon)
        val ivArrow = view.findViewById<ImageView>(R.id.ivClipboardActionArrow)
        val btnClose = view.findViewById<ImageButton>(R.id.btnCloseClipboard)
        val layoutNumberActions = view.findViewById<LinearLayout>(R.id.layoutNumberActions)
        val btnClipCall = view.findViewById<View>(R.id.btnClipCall)
        val ivClipCallIcon = view.findViewById<ImageView>(R.id.ivClipCallIcon)
        val tvClipCallText = view.findViewById<TextView>(R.id.tvClipCallText)
        val btnClipSearch = view.findViewById<View>(R.id.btnClipSearch)
        val ivClipSearchIcon = view.findViewById<ImageView>(R.id.ivClipSearchIcon)
        val tvClipSearchText = view.findViewById<TextView>(R.id.tvClipSearchText)

        val isDark = prefs.getBoolean("is_dark", false) || activity.isPrivateMode
        val rawAccentColor = Color.parseColor(prefs.getString("accent_color", "#0B57D0") ?: "#0B57D0")
        val accentColor = if (isDark) Color.parseColor("#8AB4F8") else rawAccentColor
        val coralColor = Color.parseColor("#E53935")

        if (isDark) {
            val bgCard = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 22f, activity.resources.displayMetrics)
                setColor(Color.parseColor("#1E1E1E"))
                setStroke(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1.2f, activity.resources.displayMetrics).toInt(), Color.parseColor("#333333"))
            }
            card.background = bgCard
            tvAction.setTextColor(Color.WHITE)
            tvContent.setTextColor(Color.parseColor("#C4C7C5"))

            val pillBgDark = GradientDrawable().apply {
                cornerRadius = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 18f, activity.resources.displayMetrics)
                setColor(Color.parseColor("#282828"))
                setStroke(1, Color.parseColor("#3E3E3E"))
            }
            btnClipCall.background = pillBgDark
            btnClipSearch.background = pillBgDark.constantState?.newDrawable()?.mutate()
            tvClipCallText?.setTextColor(accentColor)
            ivClipCallIcon?.setColorFilter(accentColor)
            tvClipSearchText?.setTextColor(Color.WHITE)
            ivClipSearchIcon?.setColorFilter(Color.WHITE)
        } else {
            val bgCard = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 22f, activity.resources.displayMetrics)
                setColor(Color.WHITE)
                setStroke(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1.2f, activity.resources.displayMetrics).toInt(), Color.parseColor("#E0E0E0"))
            }
            card.background = bgCard
            tvAction.setTextColor(Color.parseColor("#202124"))
            tvContent.setTextColor(Color.parseColor("#5F6368"))

            val pillBgLight = GradientDrawable().apply {
                cornerRadius = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 18f, activity.resources.displayMetrics)
                setColor(Color.parseColor("#F1F3F4"))
                setStroke(1, Color.parseColor("#D8DEE8"))
            }
            btnClipCall.background = pillBgLight
            btnClipSearch.background = pillBgLight.constantState?.newDrawable()?.mutate()
            tvClipCallText?.setTextColor(accentColor)
            ivClipCallIcon?.setColorFilter(accentColor)
            tvClipSearchText?.setTextColor(Color.parseColor("#202124"))
            ivClipSearchIcon?.setColorFilter(Color.parseColor("#5F6368"))
        }

        val rowContent = view.findViewById<View>(R.id.rowClipboardContent)

        when (clipItem) {
            is ClipboardItem.UrlItem -> {
                tvBadge.text = "COPIED LINK"
                tvBadge.setTextColor(accentColor)
                ivBadgeIcon.setImageResource(R.drawable.ic_link)
                ivBadgeIcon.setColorFilter(accentColor)
                ivType.setImageResource(R.drawable.ic_globe)
                ivType.setColorFilter(accentColor)
                ivArrow.setColorFilter(accentColor)
                tvAction.text = "Go to: ${clipItem.url}"
                tvContent.text = "Copied web address"
                layoutNumberActions.visibility = View.VISIBLE

                ivClipCallIcon?.setImageResource(R.drawable.ic_globe)
                tvClipCallText?.text = "Open Link"
                ivClipSearchIcon?.setImageResource(R.drawable.ic_search)
                tvClipSearchText?.text = "Search Web"

                btnClipCall.setOnClickListener {
                    activity.triggerStrongHaptic(it)
                    activity.loadUrlFromInput(clipItem.url)
                }
                btnClipSearch.setOnClickListener {
                    activity.triggerStrongHaptic(it)
                    activity.loadUrlFromInput(clipItem.url)
                }

                val openUrl = View.OnClickListener {
                    activity.triggerStrongHaptic(it)
                    activity.loadUrlFromInput(clipItem.url)
                }
                card.setOnClickListener(openUrl)
                rowContent?.setOnClickListener(openUrl)
            }
            is ClipboardItem.PhoneItem -> {
                tvBadge.text = "COPIED NUMBER"
                tvBadge.setTextColor(coralColor)
                ivBadgeIcon.setImageResource(R.drawable.ic_call)
                ivBadgeIcon.setColorFilter(coralColor)
                ivType.setImageResource(R.drawable.ic_call)
                ivType.setColorFilter(coralColor)
                ivArrow.setColorFilter(coralColor)
                tvAction.text = "Call or Search: ${clipItem.number}"
                tvContent.text = "Copied phone number"
                layoutNumberActions.visibility = View.VISIBLE

                ivClipCallIcon?.setImageResource(R.drawable.ic_call)
                tvClipCallText?.text = "Call Number"
                ivClipSearchIcon?.setImageResource(R.drawable.ic_search)
                tvClipSearchText?.text = "Search Web"

                btnClipCall.setOnClickListener {
                    activity.triggerStrongHaptic(it)
                    try {
                        val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${clipItem.number}"))
                        activity.startActivity(dialIntent)
                    } catch (e: Exception) {
                        Toast.makeText(activity, "Cannot dial: ${clipItem.number}", Toast.LENGTH_SHORT).show()
                    }
                }

                btnClipSearch.setOnClickListener {
                    activity.triggerStrongHaptic(it)
                    activity.loadUrlFromInput(clipItem.number)
                }

                val openPhone = View.OnClickListener {
                    activity.triggerStrongHaptic(it)
                    btnClipCall.performClick()
                }
                card.setOnClickListener(openPhone)
                rowContent?.setOnClickListener(openPhone)
            }
            is ClipboardItem.QueryItem -> {
                tvBadge.text = "COPIED TEXT"
                tvBadge.setTextColor(accentColor)
                ivBadgeIcon.setImageResource(R.drawable.ic_search)
                ivBadgeIcon.setColorFilter(accentColor)
                ivType.setImageResource(R.drawable.ic_search)
                ivType.setColorFilter(accentColor)
                ivArrow.setColorFilter(accentColor)
                tvAction.text = "Search: \"${clipItem.query}\""
                tvContent.text = "Copied text snippet"
                layoutNumberActions.visibility = View.VISIBLE

                ivClipCallIcon?.setImageResource(R.drawable.ic_search)
                tvClipCallText?.text = "Search Web"
                ivClipSearchIcon?.setImageResource(R.drawable.ic_content_paste)
                tvClipSearchText?.text = "Paste in Bar"

                btnClipCall.setOnClickListener {
                    activity.triggerStrongHaptic(it)
                    activity.loadUrlFromInput(clipItem.query)
                }
                btnClipSearch.setOnClickListener {
                    activity.triggerStrongHaptic(it)
                    activity.addressBar.setText(clipItem.query)
                    activity.addressBar.setSelection(clipItem.query.length)
                }

                val openQuery = View.OnClickListener {
                    activity.triggerStrongHaptic(it)
                    activity.loadUrlFromInput(clipItem.query)
                }
                card.setOnClickListener(openQuery)
                rowContent?.setOnClickListener(openQuery)
            }
        }

        btnClose.setOnClickListener {
            activity.triggerStrongHaptic(it)
            val textToDismiss = when (clipItem) {
                is ClipboardItem.UrlItem -> clipItem.url
                is ClipboardItem.PhoneItem -> clipItem.number
                is ClipboardItem.QueryItem -> clipItem.query
            }
            lastDismissedClip = textToDismiss
            container.removeView(view)
            container.visibility = View.GONE
        }

        return view
    }

    fun isBrandIcon(iconRes: Int): Boolean {
        return iconRes in listOf(
            R.drawable.ic_google_logo,
            R.drawable.ic_duckduckgo,
            R.drawable.ic_bing,
            R.drawable.ic_perplexity,
            R.drawable.ic_wikipedia,
            R.drawable.ic_youtube,
            R.drawable.ic_reddit,
            R.drawable.ic_facebook,
            R.drawable.ic_instagram,
            R.drawable.ic_netflix,
            R.drawable.ic_tiktok,
            R.drawable.ic_twitch,
            R.drawable.ic_pinterest,
            R.drawable.ic_spotify,
            R.drawable.ic_threads,
            R.drawable.ic_vimeo,
            R.drawable.ic_amazon,
            R.drawable.ic_x,
            R.drawable.ic_brave,
            R.drawable.ic_yahoo,
            R.drawable.ic_ecosia
        )
    }

    fun getDropdownSearchEngines(): List<SearchEngine> {
        val list = mutableListOf<SearchEngine>()
        val all = getAllEngines()
        val preferredIds = listOf("google", "bing", "duckduckgo", "perplexity", "wikipedia", "brave", "yahoo", "ecosia")
        for (id in preferredIds) {
            all.find { it.id == id }?.let { list.add(it) }
        }
        for (custom in getCustomEngines()) {
            if (!list.any { it.id == custom.id }) {
                list.add(custom)
            }
        }
        return list
    }

    /**
     * Shows sleek floating dropdown selector for default search engine.
     * Aligns right above the omnibox search engine pill container.
     */
    fun showSearchEngineDropdown(
        anchorView: View,
        chevronView: ImageView?,
        onEngineSelected: (SearchEngine) -> Unit
    ) {
        val isDark = prefs.getBoolean("is_dark", false) || activity.isPrivateMode
        val currentEngineName = prefs.getString("search_engine", "Google") ?: "Google"
        val accentColorHex = prefs.getString("accent_color", "#0A56D0") ?: "#0A56D0"
        val accentColor = Color.parseColor(accentColorHex)

        val cardBgColor = if (isDark) Color.parseColor("#25272B") else Color.parseColor("#FFFFFF")
        val strokeColor = if (isDark) Color.parseColor("#3C4043") else Color.parseColor("#DADCE0")
        val headerColor = if (isDark) Color.parseColor("#9AA0A6") else Color.parseColor("#5F6368")
        val textColor = if (isDark) Color.parseColor("#E8EAED") else Color.parseColor("#202124")

        val dpToPx = { dp: Float ->
            TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, activity.resources.displayMetrics).toInt()
        }

        // Card Container
        val cardLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(18f).toFloat()
                setColor(cardBgColor)
                setStroke(dpToPx(1f), strokeColor)
            }
            elevation = dpToPx(10f).toFloat()
            clipToOutline = true
        }

        // Header: "Search with:"
        val tvHeader = TextView(activity).apply {
            text = "Search with:"
            textSize = 12.5f
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            setTextColor(headerColor)
            setPadding(dpToPx(16f), dpToPx(14f), dpToPx(16f), dpToPx(8f))
        }
        cardLayout.addView(tvHeader)

        // Scrollable list of engines
        val scrollView = ScrollView(activity).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val itemsContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dpToPx(8f))
        }

        var popupWindowRef: PopupWindow? = null

        val engines = getDropdownSearchEngines()
        for (engine in engines) {
            val isSelected = engine.name.equals(currentEngineName, ignoreCase = true) ||
                             (engine.id == "wikipedia" && currentEngineName.startsWith("Wikipedia", ignoreCase = true))

            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dpToPx(46f)
                ).apply {
                    setMargins(dpToPx(6f), dpToPx(1f), dpToPx(6f), dpToPx(1f))
                }
                setPadding(dpToPx(12f), 0, dpToPx(14f), 0)
                isClickable = true
                isFocusable = true

                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dpToPx(12f).toFloat()
                    if (isSelected) {
                        setColor(if (isDark) Color.parseColor("#263859") else Color.parseColor("#E8F0FE"))
                    } else {
                        setColor(Color.TRANSPARENT)
                    }
                }
            }

            // Engine Icon
            val ivIcon = ImageView(activity).apply {
                layoutParams = LinearLayout.LayoutParams(dpToPx(20f), dpToPx(20f)).apply {
                    marginEnd = dpToPx(14f)
                }
                setImageResource(engine.iconRes)
                if (isBrandIcon(engine.iconRes)) {
                    clearColorFilter()
                } else {
                    setColorFilter(if (isDark) Color.WHITE else Color.parseColor("#202124"))
                }
            }
            row.addView(ivIcon)

            // Engine Name
            val tvName = TextView(activity).apply {
                text = engine.name
                textSize = 14.5f
                typeface = android.graphics.Typeface.create(
                    if (isSelected) "sans-serif-medium" else "sans-serif",
                    if (isSelected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL
                )
                setTextColor(if (isSelected && !isDark) Color.parseColor("#1A73E8") else textColor)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            row.addView(tvName)

            // Right checkmark if selected
            if (isSelected) {
                val ivCheck = ImageView(activity).apply {
                    layoutParams = LinearLayout.LayoutParams(dpToPx(18f), dpToPx(18f))
                    setImageResource(R.drawable.ic_check)
                    setColorFilter(if (isDark) Color.parseColor("#8AB4F8") else Color.parseColor("#1A73E8"))
                }
                row.addView(ivCheck)
            }

            row.setOnClickListener {
                activity.triggerStrongHaptic(isHeavy = false)
                recordEngineUsage(engine.id)
                onEngineSelected(engine)
                popupWindowRef?.dismiss()
            }

            itemsContainer.addView(row)
        }

        scrollView.addView(itemsContainer)
        cardLayout.addView(scrollView)

        val popupWidth = dpToPx(260f)
        val popupWindow = PopupWindow(
            cardLayout,
            popupWidth,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        ).apply {
            isOutsideTouchable = true
            elevation = dpToPx(10f).toFloat()
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            animationStyle = android.R.style.Animation_Dialog
        }
        popupWindowRef = popupWindow
        activeDropdownWindow?.dismiss()
        activeDropdownWindow = popupWindow

        chevronView?.animate()?.rotation(180f)?.setDuration(200)?.start()

        popupWindow.setOnDismissListener {
            if (activeDropdownWindow == popupWindow) {
                activeDropdownWindow = null
            }
            chevronView?.animate()?.rotation(0f)?.setDuration(200)?.start()
        }

        val widthSpec = View.MeasureSpec.makeMeasureSpec(popupWidth, View.MeasureSpec.EXACTLY)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(dpToPx(420f), View.MeasureSpec.AT_MOST)
        cardLayout.measure(widthSpec, heightSpec)
        val popupHeight = cardLayout.measuredHeight

        val location = IntArray(2)
        anchorView.getLocationOnScreen(location)
        val anchorX = location[0]
        val anchorY = location[1]

        val y = Math.max(dpToPx(16f), anchorY - popupHeight - dpToPx(8f))
        val x = Math.max(dpToPx(8f), anchorX)

        try {
            popupWindow.showAtLocation(anchorView, android.view.Gravity.NO_GRAVITY, x, y)
        } catch (e: Exception) {
            android.util.Log.e("HazeOmnibox", "Error showing engine dropdown", e)
            try {
                popupWindow.showAsDropDown(anchorView)
            } catch (e2: Exception) {
                android.util.Log.e("HazeOmnibox", "Error falling back to showAsDropDown", e2)
            }
        }
    }

    /**
     * Render Search Engine Selector Chips
     */
    fun renderSearchEngineChips(
        chipContainer: LinearLayout,
        onEngineSelected: (SearchEngine) -> Unit
    ) {
        chipContainer.removeAllViews()
        val currentEngineName = prefs.getString("search_engine", "Google") ?: "Google"
        val isDark = prefs.getBoolean("is_dark", false) || activity.isPrivateMode
        val accentColor = Color.parseColor(prefs.getString("accent_color", "#0B57D0") ?: "#0B57D0")

        val dp12 = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 12f, activity.resources.displayMetrics).toInt()
        val dp8 = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 8f, activity.resources.displayMetrics).toInt()
        val dp6 = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 6f, activity.resources.displayMetrics).toInt()
        val dp16 = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 16f, activity.resources.displayMetrics).toInt()

        val stripEngines = getStripEngines()

        for (engine in stripEngines) {
            val isSelected = engine.name.equals(currentEngineName, ignoreCase = true)
            val chip = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 32f, activity.resources.displayMetrics).toInt()
                ).apply {
                    setMargins(dp6, 0, dp6, 0)
                }
                setPadding(dp12, 0, dp12, 0)
                isClickable = true
                isFocusable = true
            }

            val bg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 16f, activity.resources.displayMetrics)
                if (isSelected) {
                    setColor(accentColor)
                } else {
                    setColor(if (isDark) Color.parseColor("#1E1E1E") else Color.parseColor("#F1F3F4"))
                    setStroke(1, if (isDark) Color.parseColor("#333333") else Color.parseColor("#DADCE0"))
                }
            }
            chip.background = bg

            val isBranded = isBrandIcon(engine.iconRes)

            val iv = ImageView(activity).apply {
                layoutParams = LinearLayout.LayoutParams(dp16, dp16).apply {
                    setMargins(0, 0, dp6, 0)
                }
                setImageResource(engine.iconRes)
                if (isBranded) {
                    clearColorFilter()
                } else {
                    if (isSelected) {
                        setColorFilter(Color.WHITE)
                    } else {
                        setColorFilter(if (isDark) Color.parseColor("#C4C7C5") else Color.parseColor("#5F6368"))
                    }
                }
            }
            chip.addView(iv)

            val tv = TextView(activity).apply {
                text = engine.name
                textSize = 12f
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                setTextColor(if (isSelected) Color.WHITE else if (isDark) Color.parseColor("#E8EAED") else Color.parseColor("#3C4043"))
            }
            chip.addView(tv)

            chip.setOnClickListener {
                activity.triggerStrongHaptic(it)
                recordEngineUsage(engine.id)
                val typed = activity.getUserTypedQuery()
                if (typed.isNotEmpty()) {
                    // Smart search: typing a query and clicking directly searches that destination
                    val url = buildSearchUrl(engine, typed)
                    activity.loadUrlFromInput(url)
                } else {
                    prefs.edit().putString("search_engine", engine.name).apply()
                    onEngineSelected(engine)
                    renderSearchEngineChips(chipContainer, onEngineSelected)
                }
            }

            chipContainer.addView(chip)
        }

        // "More" Chip -> Opens Full Search Destinations Catalog
        val moreChip = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 32f, activity.resources.displayMetrics).toInt()
            ).apply {
                setMargins(dp6, 0, dp6, 0)
            }
            setPadding(dp12, 0, dp12, 0)
            isClickable = true
            isFocusable = true
        }

        val moreBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 16f, activity.resources.displayMetrics)
            setColor(if (isDark) Color.parseColor("#252830") else Color.parseColor("#F1F3F4"))
            setStroke(1, accentColor)
        }
        moreChip.background = moreBg

        val moreIv = ImageView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(dp16, dp16).apply {
                setMargins(0, 0, dp6, 0)
            }
            setImageResource(R.drawable.ic_grid)
            setColorFilter(accentColor)
        }
        moreChip.addView(moreIv)

        val moreTv = TextView(activity).apply {
            text = "More"
            textSize = 12f
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            setTextColor(accentColor)
        }
        moreChip.addView(moreTv)

        moreChip.setOnClickListener {
            activity.triggerStrongHaptic(it)
            showSearchDestinationsCatalog(chipContainer, onEngineSelected)
        }

        chipContainer.addView(moreChip)
    }

    /**
     * BottomSheet Catalog for all Search Destinations across all categories.
     * Allows searching, category filtering, 1-tap pinning to omnibox strip,
     * smart direct searching, and adding custom engines.
     */
    fun showSearchDestinationsCatalog(
        chipContainer: LinearLayout,
        onEngineSelected: (SearchEngine) -> Unit,
        initialCategory: String = "All"
    ) {
        val dialog = BottomSheetDialog(activity)
        val isDark = prefs.getBoolean("is_dark", false) || activity.isPrivateMode
        val rawAccent = Color.parseColor(prefs.getString("accent_color", "#0B57D0") ?: "#0B57D0")
        val accentColor = if (isDark) Color.parseColor("#8AB4F8") else rawAccent
        val bgColor = if (isDark) Color.parseColor("#18191A") else Color.WHITE
        val cardBgColor = if (isDark) Color.parseColor("#242526") else Color.parseColor("#F8F9FA")
        val textColor = if (isDark) Color.WHITE else Color.parseColor("#202124")
        val subTextColor = if (isDark) Color.parseColor("#C4C7C5") else Color.parseColor("#5F6368")
        val borderColor = if (isDark) Color.parseColor("#38393A") else Color.parseColor("#E0E0E0")

        val rootLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val pad = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 16f, activity.resources.displayMetrics).toInt()
            setPadding(pad, pad / 2, pad, pad)
            background = GradientDrawable().apply {
                setColor(bgColor)
                cornerRadii = floatArrayOf(48f, 48f, 48f, 48f, 0f, 0f, 0f, 0f)
            }
        }

        // Top drag handle
        val handle = View(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 40f, activity.resources.displayMetrics).toInt(),
                TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 4f, activity.resources.displayMetrics).toInt()
            ).apply {
                gravity = android.view.Gravity.CENTER_HORIZONTAL
                setMargins(0, 0, 0, TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 12f, activity.resources.displayMetrics).toInt())
            }
            background = GradientDrawable().apply {
                setColor(if (isDark) Color.parseColor("#4E5052") else Color.parseColor("#D0D0D0"))
                cornerRadius = 8f
            }
        }
        rootLayout.addView(handle)

        // Header row: Title + Reset Pins + Close
        val headerRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        val tvTitle = TextView(activity).apply {
            text = "Search Destinations"
            textSize = 18f
            typeface = android.graphics.Typeface.create("sans-serif-bold", android.graphics.Typeface.BOLD)
            setTextColor(textColor)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        headerRow.addView(tvTitle)

        val btnResetPins = TextView(activity).apply {
            text = "Reset Pins"
            textSize = 12f
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            setTextColor(accentColor)
            val dp8 = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 8f, activity.resources.displayMetrics).toInt()
            val dp4 = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 4f, activity.resources.displayMetrics).toInt()
            setPadding(dp8, dp4, dp8, dp4)
            isClickable = true
            isFocusable = true
        }
        headerRow.addView(btnResetPins)

        val btnClose = ImageView(activity).apply {
            setImageResource(R.drawable.ic_close)
            setColorFilter(subTextColor)
            val dp24 = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 24f, activity.resources.displayMetrics).toInt()
            layoutParams = LinearLayout.LayoutParams(dp24, dp24).apply {
                setMargins(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 8f, activity.resources.displayMetrics).toInt(), 0, 0, 0)
            }
            isClickable = true
            isFocusable = true
            setOnClickListener {
                activity.triggerStrongHaptic(it)
                dialog.dismiss()
            }
        }
        headerRow.addView(btnClose)
        rootLayout.addView(headerRow)

        val tvSub = TextView(activity).apply {
            text = "Tap to search or set default. Pin to add to your omnibox strip."
            textSize = 12f
            setTextColor(subTextColor)
            val dp4 = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 4f, activity.resources.displayMetrics).toInt()
            setPadding(0, dp4, 0, dp4 * 2)
        }
        rootLayout.addView(tvSub)

        // Filter / Search Input
        val etFilter = EditText(activity).apply {
            hint = "Filter destinations or shortcut prefixes..."
            textSize = 14f
            setTextColor(textColor)
            setHintTextColor(subTextColor)
            setSingleLine()
            val dp12 = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 12f, activity.resources.displayMetrics).toInt()
            setPadding(dp12, dp12, dp12, dp12)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 12f, activity.resources.displayMetrics)
                setColor(cardBgColor)
                setStroke(1, borderColor)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, dp12 / 2, 0, dp12)
            }
        }
        rootLayout.addView(etFilter)

        // Horizontal Category Filter Bar
        val categoryScroll = HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 10f, activity.resources.displayMetrics).toInt())
            }
        }
        val categoryContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        categoryScroll.addView(categoryContainer)
        rootLayout.addView(categoryScroll)

        // Destinations List inside ScrollView
        val listScrollView = ScrollView(activity).apply {
            isVerticalScrollBarEnabled = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 360f, activity.resources.displayMetrics).toInt()
            )
        }
        val listContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        listScrollView.addView(listContainer)
        rootLayout.addView(listScrollView)

        // Bottom "+ Add Custom Destination" Bar
        val bottomBar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
            val dp10 = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 10f, activity.resources.displayMetrics).toInt()
            setPadding(0, dp10, 0, 0)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        val btnAddCustom = TextView(activity).apply {
            text = "+ Add Custom Destination"
            textSize = 13f
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            setTextColor(accentColor)
            val dp8 = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 8f, activity.resources.displayMetrics).toInt()
            setPadding(dp8 * 2, dp8, dp8 * 2, dp8)
            background = GradientDrawable().apply {
                cornerRadius = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 16f, activity.resources.displayMetrics)
                setColor(if (isDark) Color.parseColor("#252830") else Color.parseColor("#EEF3FD"))
                setStroke(1, accentColor)
            }
            isClickable = true
            isFocusable = true
            setOnClickListener {
                activity.triggerStrongHaptic(it)
                dialog.dismiss()
                showAddSearchEngineDialog(chipContainer, onEngineSelected)
            }
        }
        bottomBar.addView(btnAddCustom)
        rootLayout.addView(bottomBar)

        val categories = listOf(
            "All",
            "Essential",
            "Video & Streaming",
            "Social & Community",
            "Shopping",
            "AI Search & Assistants",
            "Knowledge & Technical Search",
            "Travel, Maps & Bookings",
            "Email & Productivity"
        )
        var selectedCategory = initialCategory
        var filterText = ""

        fun renderList() {
            listContainer.removeAllViews()
            val allEngines = getAllEngines()
            val currentEngineName = prefs.getString("search_engine", "Google") ?: "Google"
            val dp10 = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 10f, activity.resources.displayMetrics).toInt()
            val dp8 = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 8f, activity.resources.displayMetrics).toInt()
            val dp4 = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 4f, activity.resources.displayMetrics).toInt()
            val dp6 = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 6f, activity.resources.displayMetrics).toInt()
            val dp38 = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 38f, activity.resources.displayMetrics).toInt()
            val dp20 = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 20f, activity.resources.displayMetrics).toInt()
            val dp22 = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 22f, activity.resources.displayMetrics).toInt()
            val dp24 = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 24f, activity.resources.displayMetrics).toInt()
            val dp12 = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 12f, activity.resources.displayMetrics).toInt()

            val filtered = allEngines.filter { engine ->
                val matchesCategory = (selectedCategory == "All") || engine.category.equals(selectedCategory, ignoreCase = true)
                val matchesFilter = filterText.isEmpty() ||
                        engine.name.contains(filterText, ignoreCase = true) ||
                        engine.prefix.contains(filterText, ignoreCase = true) ||
                        engine.description.contains(filterText, ignoreCase = true) ||
                        engine.category.contains(filterText, ignoreCase = true)
                matchesCategory && matchesFilter
            }

            if (filtered.isEmpty()) {
                val tvEmpty = TextView(activity).apply {
                    text = "No destinations found"
                    textSize = 14f
                    setTextColor(subTextColor)
                    gravity = android.view.Gravity.CENTER
                    val padEmpty = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 32f, activity.resources.displayMetrics).toInt()
                    setPadding(0, padEmpty, 0, padEmpty)
                }
                listContainer.addView(tvEmpty)
                return
            }

            for (engine in filtered) {
                val isCurrentDefault = engine.name.equals(currentEngineName, ignoreCase = true)
                var isPinned = isEnginePinned(engine.id)

                val itemCard = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(dp10, dp10, dp10, dp10)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(0, 0, 0, dp6)
                    }
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 14f, activity.resources.displayMetrics)
                        setColor(cardBgColor)
                        setStroke(1, borderColor)
                    }
                    isClickable = true
                    isFocusable = true
                }

                val isBranded = isBrandIcon(engine.iconRes)

                // Left squircle icon container
                val iconBox = LinearLayout(activity).apply {
                    gravity = android.view.Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(dp38, dp38)
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 10f, activity.resources.displayMetrics)
                        setColor(if (isDark) Color.parseColor("#323436") else Color.parseColor("#EAEAEA"))
                    }
                }
                val ivIcon = ImageView(activity).apply {
                    layoutParams = LinearLayout.LayoutParams(dp22, dp22)
                    setImageResource(engine.iconRes)
                    if (isBranded) {
                        clearColorFilter()
                    } else {
                        setColorFilter(if (isDark) Color.WHITE else Color.parseColor("#202124"))
                    }
                }
                iconBox.addView(ivIcon)
                itemCard.addView(iconBox)

                // Middle: Ultra-simple site name
                val tvName = TextView(activity).apply {
                    text = engine.name
                    textSize = 15f
                    typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                    setTextColor(textColor)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        setMargins(dp12, 0, dp8, 0)
                    }
                }
                itemCard.addView(tvName)

                // Optional Active Badge
                if (isCurrentDefault) {
                    val tvDefault = TextView(activity).apply {
                        text = "Active"
                        textSize = 9.5f
                        typeface = android.graphics.Typeface.create("sans-serif-bold", android.graphics.Typeface.BOLD)
                        setTextColor(Color.WHITE)
                        val padH = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 7f, activity.resources.displayMetrics).toInt()
                        val padV = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 2.5f, activity.resources.displayMetrics).toInt()
                        setPadding(padH, padV, padH, padV)
                        background = GradientDrawable().apply {
                            shape = GradientDrawable.RECTANGLE
                            cornerRadius = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 6f, activity.resources.displayMetrics)
                            setColor(accentColor)
                        }
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.WRAP_CONTENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply {
                            setMargins(0, 0, dp8, 0)
                        }
                    }
                    itemCard.addView(tvDefault)
                }

                // Right Pin Button
                val pinBtn = ImageView(activity).apply {
                    layoutParams = LinearLayout.LayoutParams(dp38, dp38)
                    setPadding(dp8, dp8, dp8, dp8)
                    setImageResource(if (isPinned) R.drawable.ic_pin_filled else R.drawable.ic_pin)
                    setColorFilter(if (isPinned) accentColor else subTextColor)
                    isClickable = true
                    isFocusable = true
                }

                pinBtn.setOnClickListener {
                    activity.triggerStrongHaptic(it)
                    val nowPinned = togglePinEngine(engine.id)
                    isPinned = nowPinned
                    pinBtn.setImageResource(if (nowPinned) R.drawable.ic_pin_filled else R.drawable.ic_pin)
                    pinBtn.setColorFilter(if (nowPinned) accentColor else subTextColor)
                    renderSearchEngineChips(chipContainer, onEngineSelected)
                    val msg = if (nowPinned) "${engine.name} pinned to strip" else "${engine.name} unpinned from strip"
                    Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()
                }

                itemCard.addView(pinBtn)

                // Card Click: smart search if query is typed; otherwise set default engine
                itemCard.setOnClickListener {
                    activity.triggerStrongHaptic(it)
                    recordEngineUsage(engine.id)
                    val typed = activity.getUserTypedQuery()
                    dialog.dismiss()
                    if (typed.isNotEmpty()) {
                        val url = buildSearchUrl(engine, typed)
                        activity.loadUrlFromInput(url)
                    } else {
                        prefs.edit().putString("search_engine", engine.name).apply()
                        onEngineSelected(engine)
                        renderSearchEngineChips(chipContainer, onEngineSelected)
                        Toast.makeText(activity, "Default search set to ${engine.name}", Toast.LENGTH_SHORT).show()
                    }
                }

                listContainer.addView(itemCard)
            }
        }

        fun renderCategoryPills() {
            categoryContainer.removeAllViews()
            val dp10 = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 10f, activity.resources.displayMetrics).toInt()
            val dp6 = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 6f, activity.resources.displayMetrics).toInt()
            val dp4 = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 4f, activity.resources.displayMetrics).toInt()

            for (cat in categories) {
                val isCatSelected = cat == selectedCategory
                val pill = TextView(activity).apply {
                    text = cat
                    textSize = 12f
                    typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
                    setPadding(dp10, dp6, dp10, dp6)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(dp4, 0, dp4, 0)
                    }
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 14f, activity.resources.displayMetrics)
                        if (isCatSelected) {
                            setColor(accentColor)
                        } else {
                            setColor(cardBgColor)
                            setStroke(1, borderColor)
                        }
                    }
                    setTextColor(if (isCatSelected) Color.WHITE else textColor)
                    isClickable = true
                    isFocusable = true
                    setOnClickListener {
                        activity.triggerStrongHaptic(it)
                        selectedCategory = cat
                        renderCategoryPills()
                        renderList()
                    }
                }
                categoryContainer.addView(pill)
            }
        }

        btnResetPins.setOnClickListener {
            activity.triggerStrongHaptic(it)
            setPinnedEngineIds(DEFAULT_PINNED_IDS)
            renderSearchEngineChips(chipContainer, onEngineSelected)
            renderList()
            Toast.makeText(activity, "Search strip reset to default", Toast.LENGTH_SHORT).show()
        }

        etFilter.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterText = s?.toString()?.trim() ?: ""
                renderList()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        renderCategoryPills()
        renderList()

        dialog.setContentView(rootLayout)
        dialog.window?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.setBackgroundColor(Color.TRANSPARENT)
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
        dialog.show()
    }

    /**
     * Dialog to add a custom search engine
     */
    private fun showAddSearchEngineDialog(
        chipContainer: LinearLayout,
        onEngineSelected: (SearchEngine) -> Unit
    ) {
        val dialogLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val pad = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 20f, activity.resources.displayMetrics).toInt()
            setPadding(pad, pad, pad, pad)
        }

        val etName = EditText(activity).apply {
            hint = "Destination Name (e.g. Stack Overflow)"
            setSingleLine()
        }
        val etPrefix = EditText(activity).apply {
            hint = "Shortcut Prefix (e.g. so)"
            setSingleLine()
        }
        val etUrl = EditText(activity).apply {
            hint = "Search URL with %s (e.g. https://stackoverflow.com/search?q=%s)"
            setSingleLine()
        }

        dialogLayout.addView(etName)
        dialogLayout.addView(etPrefix)
        dialogLayout.addView(etUrl)

        AlertDialog.Builder(activity)
            .setTitle("Add Custom Destination")
            .setView(dialogLayout)
            .setPositiveButton("Save") { _, _ ->
                val name = etName.text.toString().trim()
                val prefix = etPrefix.text.toString().trim()
                val url = etUrl.text.toString().trim()
                if (name.isNotEmpty() && prefix.isNotEmpty() && url.isNotEmpty()) {
                    saveCustomEngine(name, prefix, url)
                    renderSearchEngineChips(chipContainer, onEngineSelected)
                    Toast.makeText(activity, "Added custom destination: $name", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(activity, "Please fill in all fields", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Start zero-permission Google Play Services QR / Barcode scanner
     */
    fun startQrScanner() {
        try {
            val options = GmsBarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE, Barcode.FORMAT_ALL_FORMATS)
                .enableAutoZoom()
                .build()

            val scanner = GmsBarcodeScanning.getClient(activity, options)
            scanner.startScan()
                .addOnSuccessListener { barcode: Barcode ->
                    val rawValue = barcode.rawValue ?: barcode.displayValue ?: ""
                    if (rawValue.isNotBlank()) {
                        activity.triggerStrongHaptic()
                        activity.findViewById<EditText>(R.id.addressBar)?.setText(rawValue)
                        activity.loadUrlFromInput(rawValue)
                    }
                }
                .addOnCanceledListener {
                    // Closed by user
                }
                .addOnFailureListener { e: Exception ->
                    val msg = e.localizedMessage ?: e.message ?: "Scanner unavailable"
                    Toast.makeText(activity, "QR Scanner: $msg", Toast.LENGTH_SHORT).show()
                }
        } catch (e: Exception) {
            Toast.makeText(activity, "Scanner not available: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
