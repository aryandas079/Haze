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

/**
 * HazeOmniboxManager
 * Manages Omnibox features:
 * - Smart Clipboard Detection (URLs, phone numbers, search queries)
 * - Search Engine Selector chips (Google, Bing, DuckDuckGo, Brave, YouTube, GitHub, Wikipedia, etc.)
 * - Search Shortcuts & Prefix routing (g, yt, gh, w, b, ddg, br, r, a, custom)
 * - Custom Search Engines (creation & persistence)
 * - QR / Barcode Scanner integration
 */
class HazeOmniboxManager(
    private val activity: MainActivity,
    private val prefs: SharedPreferences
) {
    data class SearchEngine(
        val id: String,
        val name: String,
        val prefix: String,
        val searchUrlPattern: String,
        val iconRes: Int,
        val isCustom: Boolean = false
    )

    sealed class ClipboardItem {
        data class UrlItem(val url: String) : ClipboardItem()
        data class PhoneItem(val number: String) : ClipboardItem()
        data class QueryItem(val query: String) : ClipboardItem()
    }

    private var lastDismissedClip: String? = null

    // Built-in Search Engines
    val defaultEngines = listOf(
        SearchEngine("google", "Google", "g", "https://www.google.com/search?q=%s", R.drawable.ic_google_logo),
        SearchEngine("duckduckgo", "DuckDuckGo", "ddg", "https://duckduckgo.com/?q=%s", R.drawable.ic_duckduckgo),
        SearchEngine("bing", "Bing", "b", "https://www.bing.com/search?q=%s", R.drawable.ic_search),
        SearchEngine("brave", "Brave", "br", "https://search.brave.com/search?q=%s", R.drawable.ic_search),
        SearchEngine("youtube", "YouTube", "yt", "https://m.youtube.com/results?search_query=%s", R.drawable.ic_youtube),
        SearchEngine("github", "GitHub", "gh", "https://github.com/search?q=%s", R.drawable.ic_github),
        SearchEngine("wikipedia", "Wikipedia", "w", "https://en.wikipedia.org/wiki/Special:Search?search=%s", R.drawable.ic_wikipedia),
        SearchEngine("reddit", "Reddit", "r", "https://www.reddit.com/search/?q=%s", R.drawable.ic_search),
        SearchEngine("amazon", "Amazon", "a", "https://www.amazon.com/s?k=%s", R.drawable.ic_search)
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
                        isCustom = true
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

    fun saveCustomEngine(name: String, prefix: String, urlPattern: String): Boolean {
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
                isCustom = true
            )
        )
        val array = JSONArray()
        for (item in customList) {
            val obj = JSONObject()
            obj.put("id", item.id)
            obj.put("name", item.name)
            obj.put("prefix", item.prefix)
            obj.put("url", item.searchUrlPattern)
            array.put(obj)
        }
        return prefs.edit().putString("custom_search_engines_json", array.toString()).commit()
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
        val accentColor = Color.parseColor(prefs.getString("accent_color", "#0B57D0") ?: "#0B57D0")
        val coralColor = Color.parseColor("#E53935")

        if (isDark) {
            val bgCard = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 22f, activity.resources.displayMetrics)
                setColor(Color.parseColor("#1E222A"))
                setStroke(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1.2f, activity.resources.displayMetrics).toInt(), Color.parseColor("#2E3644"))
            }
            card.background = bgCard
            tvAction.setTextColor(Color.WHITE)
            tvContent.setTextColor(Color.parseColor("#9AA0A6"))

            val pillBgDark = GradientDrawable().apply {
                cornerRadius = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 18f, activity.resources.displayMetrics)
                setColor(Color.parseColor("#282D37"))
                setStroke(1, Color.parseColor("#3E4654"))
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

    /**
     * Render Search Engine Selector Chips
     */
    fun renderSearchEngineChips(
        chipContainer: LinearLayout,
        onEngineSelected: (SearchEngine) -> Unit
    ) {
        chipContainer.removeAllViews()
        val currentEngineName = prefs.getString("search_engine", "Google") ?: "Google"
        val isDark = prefs.getBoolean("is_dark", false)
        val accentColor = Color.parseColor(prefs.getString("accent_color", "#1A73E8") ?: "#1A73E8")

        val allEngines = getAllEngines()
        val dp12 = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 12f, activity.resources.displayMetrics).toInt()
        val dp8 = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 8f, activity.resources.displayMetrics).toInt()
        val dp6 = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 6f, activity.resources.displayMetrics).toInt()
        val dp16 = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 16f, activity.resources.displayMetrics).toInt()

        for (engine in allEngines) {
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
                    setColor(if (isDark) Color.parseColor("#252830") else Color.parseColor("#F1F3F4"))
                    setStroke(1, if (isDark) Color.parseColor("#383C48") else Color.parseColor("#DADCE0"))
                }
            }
            chip.background = bg

            val iv = ImageView(activity).apply {
                layoutParams = LinearLayout.LayoutParams(dp16, dp16).apply {
                    setMargins(0, 0, dp6, 0)
                }
                setImageResource(engine.iconRes)
                if (isSelected) {
                    setColorFilter(Color.WHITE)
                } else {
                    setColorFilter(if (isDark) Color.parseColor("#C4C7C5") else Color.parseColor("#5F6368"))
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
                prefs.edit().putString("search_engine", engine.name).apply()
                onEngineSelected(engine)
                renderSearchEngineChips(chipContainer, onEngineSelected)
            }

            chipContainer.addView(chip)
        }

        // "+ Add Engine" Chip
        val addChip = LinearLayout(activity).apply {
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

        val addBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 16f, activity.resources.displayMetrics)
            setColor(if (isDark) Color.parseColor("#252830") else Color.parseColor("#F1F3F4"))
            setStroke(1, accentColor)
        }
        addChip.background = addBg

        val addIv = ImageView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(dp16, dp16).apply {
                setMargins(0, 0, dp6, 0)
            }
            setImageResource(R.drawable.ic_add)
            setColorFilter(accentColor)
        }
        addChip.addView(addIv)

        val addTv = TextView(activity).apply {
            text = "Add Engine"
            textSize = 12f
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            setTextColor(accentColor)
        }
        addChip.addView(addTv)

        addChip.setOnClickListener {
            activity.triggerStrongHaptic(it)
            showAddSearchEngineDialog(chipContainer, onEngineSelected)
        }

        chipContainer.addView(addChip)
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
            hint = "Engine Name (e.g. Stack Overflow)"
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
            .setTitle("Add Custom Search Engine")
            .setView(dialogLayout)
            .setPositiveButton("Save") { _, _ ->
                val name = etName.text.toString().trim()
                val prefix = etPrefix.text.toString().trim()
                val url = etUrl.text.toString().trim()
                if (name.isNotEmpty() && prefix.isNotEmpty() && url.isNotEmpty()) {
                    saveCustomEngine(name, prefix, url)
                    renderSearchEngineChips(chipContainer, onEngineSelected)
                    Toast.makeText(activity, "Added custom engine: $name", Toast.LENGTH_SHORT).show()
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
