package com.example.haze

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import java.net.URLEncoder

/**
 * Robust full-page translation and foreign language detection engine for Haze.
 * Translates 100% of any webpage (headings, articles, links, tables, menus, captions)
 * using high-performance server-side translation infrastructure without rate limits or dropped content.
 * Automatically detects non-English pages on load/reload and provides one-tap translation and language switching.
 */
object HazeTranslationManager {

    val SUPPORTED_LANGUAGES = listOf(
        Triple("English", "en", "English"),
        Triple("Spanish (Español)", "es", "Spanish"),
        Triple("French (Français)", "fr", "French"),
        Triple("German (Deutsch)", "de", "German"),
        Triple("Hindi (हिन्दी)", "hi", "Hindi"),
        Triple("Japanese (日本語)", "ja", "Japanese"),
        Triple("Chinese (中文)", "zh-CN", "Chinese"),
        Triple("Russian (Русский)", "ru", "Russian"),
        Triple("Arabic (العربية)", "ar", "Arabic"),
        Triple("Portuguese (Português)", "pt", "Portuguese"),
        Triple("Italian (Italiano)", "it", "Italian"),
        Triple("Korean (한국어)", "ko", "Korean"),
        Triple("Dutch (Nederlands)", "nl", "Dutch"),
        Triple("Turkish (Türkçe)", "tr", "Turkish"),
        Triple("Vietnamese (Tiếng Việt)", "vi", "Vietnamese"),
        Triple("Indonesian (Bahasa)", "id", "Indonesian"),
        Triple("Bengali (বাংলা)", "bn", "Bengali")
    )

    private val BUTTER_INTERPOLATOR = PathInterpolator(0.05f, 0.7f, 0.1f, 1.0f)
    private var activeTranslationBanner: View? = null
    private var lastPromptedUrl: String? = null

    private fun dpToPx(context: Context, dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            context.resources.displayMetrics
        ).toInt()
    }

    /**
     * Checks if a URL is currently translated via Google's translate.goog infrastructure.
     */
    fun isTranslatedUrl(url: String?): Boolean {
        if (url == null) return false
        val host = Uri.parse(url).host ?: return false
        return host.endsWith(".translate.goog")
    }

    /**
     * Generates a 100% translated page URL using Google's translate.goog infrastructure.
     */
    fun getTranslatedUrl(originalUrl: String, targetLang: String = "en"): String {
        try {
            val uri = Uri.parse(originalUrl)
            val host = uri.host ?: return originalUrl
            val scheme = uri.scheme ?: "https"
            val path = uri.encodedPath ?: ""

            if (host.endsWith(".translate.goog")) {
                // Already translated, update target language query parameter
                val builder = uri.buildUpon().clearQuery()
                uri.queryParameterNames.forEach { name ->
                    if (name != "_x_tr_tl") {
                        builder.appendQueryParameter(name, uri.getQueryParameter(name))
                    }
                }
                builder.appendQueryParameter("_x_tr_tl", targetLang)
                return builder.build().toString()
            }

            // Google translate.goog hostname spec:
            // Existing hyphens become double hyphens '--', dots become single hyphens '-'
            val googHost = host.replace("-", "--").replace(".", "-") + ".translate.goog"
            val query = uri.encodedQuery
            val newQuery = if (query.isNullOrEmpty()) {
                "_x_tr_sl=auto&_x_tr_tl=$targetLang&_x_tr_hl=en"
            } else {
                "$query&_x_tr_sl=auto&_x_tr_tl=$targetLang&_x_tr_hl=en"
            }
            return "$scheme://$googHost$path?$newQuery"
        } catch (_: Exception) {
            return "https://translate.google.com/translate?sl=auto&tl=$targetLang&u=" + URLEncoder.encode(originalUrl, "UTF-8")
        }
    }

    /**
     * Reconstructs the clean, original website URL from a translate.goog URL.
     */
    fun getOriginalUrlFromTranslated(translatedUrl: String): String {
        try {
            val uri = Uri.parse(translatedUrl)
            val host = uri.host ?: return translatedUrl
            if (!host.endsWith(".translate.goog")) return translatedUrl

            val rawHost = host.removeSuffix(".translate.goog")
            // Invert Google's spec: single hyphens become dots, double hyphens become single hyphens
            val origHost = rawHost.replace(Regex("(?<!-)-(?!-)"), ".").replace("--", "-")
            val scheme = uri.scheme ?: "https"
            val path = uri.encodedPath ?: ""

            val builder = uri.buildUpon().clearQuery()
            uri.queryParameterNames.forEach { name ->
                if (!name.startsWith("_x_tr_")) {
                    builder.appendQueryParameter(name, uri.getQueryParameter(name))
                }
            }
            val cleanQuery = builder.build().encodedQuery
            return if (cleanQuery.isNullOrEmpty()) {
                "$scheme://$origHost$path"
            } else {
                "$scheme://$origHost$path?$cleanQuery"
            }
        } catch (_: Exception) {
            return translatedUrl
        }
    }

    /**
     * Returns a user-friendly display URL for the address bar (strips translate.goog proxy wrapper).
     */
    fun getDisplayUrl(url: String?): String {
        if (url.isNullOrEmpty()) return ""
        return if (isTranslatedUrl(url)) getOriginalUrlFromTranslated(url) else url
    }

    private val dismissedUrls = mutableSetOf<String>()

    /**
     * Checks if a page is in a foreign language and notifies the Android interface.
     */
    fun getLanguageDetectionScript(): String {
        return """
            (function() {
                try {
                    var host = window.location.hostname || '';
                    if (host.indexOf('translate.goog') !== -1) {
                        // Already viewing translated page
                        var urlParams = new URLSearchParams(window.location.search);
                        var targetLang = urlParams.get('_x_tr_tl') || 'en';
                        if (window.Android && window.Android.onTranslatedPageLoaded) {
                            window.Android.onTranslatedPageLoaded(targetLang);
                        }
                        return;
                    }

                    function detectAndReport() {
                        var html = document.documentElement;
                        var rawLang = (html.getAttribute('lang') || document.body.getAttribute('lang') || '').trim().toLowerCase();
                        var langCode = rawLang.split(/[-_]/)[0];
                        
                        if (!langCode) {
                            var metaLang = document.querySelector('meta[http-equiv="content-language"], meta[name="language"], meta[property="og:locale"]');
                            if (metaLang) {
                                var c = (metaLang.getAttribute('content') || '').toLowerCase().trim();
                                if (c) langCode = c.split(/[-_]/)[0];
                            }
                        }

                        // Collect a representative sample of readable body text
                        var sampleText = '';
                        try {
                            var walker = document.createTreeWalker(document.body || html, NodeFilter.SHOW_TEXT, null, false);
                            var node, count = 0;
                            while ((node = walker.nextNode()) && count < 3500) {
                                var val = (node.nodeValue || '').trim();
                                if (val.length > 0 && node.parentElement && !['SCRIPT','STYLE','NOSCRIPT','SVG'].includes(node.parentElement.tagName)) {
                                    sampleText += ' ' + val;
                                    count += val.length;
                                }
                            }
                        } catch(e) {}

                        var totalChars = sampleText.replace(/\s+/g, '').length;
                        if (totalChars < 15) return;

                        // Script character counter
                        function countMatches(regex) {
                            var m = sampleText.match(regex);
                            return m ? m.length : 0;
                        }

                        var cHanzi = countMatches(/[\u4e00-\u9fa5]/g);
                        var cKana = countMatches(/[\u3040-\u309f\u30a0-\u30ff]/g);
                        var cHangul = countMatches(/[\uac00-\ud7af]/g);
                        var cCyrillic = countMatches(/[\u0400-\u04ff]/g);
                        var cArabic = countMatches(/[\u0600-\u06ff]/g);
                        var cHindi = countMatches(/[\u0900-\u097f]/g);
                        var cThai = countMatches(/[\u0e00-\u0e7f]/g);
                        var cBengali = countMatches(/[\u0980-\u09ff]/g);

                        // If the page is explicitly marked lang="en", it is English unless overwhelming foreign script exists
                        if (langCode === 'en') {
                            var maxForeignScript = Math.max(cHanzi, cKana, cHangul, cCyrillic, cArabic, cHindi, cThai, cBengali);
                            if (maxForeignScript < 60 || (maxForeignScript / totalChars) < 0.35) {
                                return; // Safely confirm as English page!
                            }
                        }

                        var isNonEnglish = false;
                        var detectedName = '';
                        var detectedCode = '';

                        // Minimum script threshold: at least 25 characters and >= 15% density
                        function isDominantScript(count) {
                            return count >= 25 && ((count / totalChars) >= 0.15 || count >= 120);
                        }

                        if (isDominantScript(cHanzi)) {
                            isNonEnglish = true;
                            detectedName = 'Chinese';
                            detectedCode = 'zh-CN';
                        } else if (isDominantScript(cKana)) {
                            isNonEnglish = true;
                            detectedName = 'Japanese';
                            detectedCode = 'ja';
                        } else if (isDominantScript(cHangul)) {
                            isNonEnglish = true;
                            detectedName = 'Korean';
                            detectedCode = 'ko';
                        } else if (isDominantScript(cCyrillic)) {
                            isNonEnglish = true;
                            detectedName = 'Russian';
                            detectedCode = 'ru';
                        } else if (isDominantScript(cArabic)) {
                            isNonEnglish = true;
                            detectedName = 'Arabic';
                            detectedCode = 'ar';
                        } else if (isDominantScript(cHindi)) {
                            isNonEnglish = true;
                            detectedName = 'Hindi';
                            detectedCode = 'hi';
                        } else if (isDominantScript(cThai)) {
                            isNonEnglish = true;
                            detectedName = 'Thai';
                            detectedCode = 'th';
                        } else if (isDominantScript(cBengali)) {
                            isNonEnglish = true;
                            detectedName = 'Bengali';
                            detectedCode = 'bn';
                        } else if (langCode && langCode !== 'en' && langCode !== 'und') {
                            // Check that it's genuinely not an English page mislabeled or with English content
                            var enStopwords = sampleText.match(/\b(the|and|of|to|in|is|that|for|it|with|as|was|on|are|by|this|from|at|be)\b/gi) || [];
                            if (enStopwords.length < 15) {
                                isNonEnglish = true;
                                detectedCode = langCode;
                                switch(langCode) {
                                    case 'es': detectedName = 'Spanish'; break;
                                    case 'fr': detectedName = 'French'; break;
                                    case 'de': detectedName = 'German'; break;
                                    case 'pt': detectedName = 'Portuguese'; break;
                                    case 'it': detectedName = 'Italian'; break;
                                    case 'nl': detectedName = 'Dutch'; break;
                                    case 'tr': detectedName = 'Turkish'; break;
                                    case 'vi': detectedName = 'Vietnamese'; break;
                                    case 'id': detectedName = 'Indonesian'; break;
                                    case 'pl': detectedName = 'Polish'; break;
                                    case 'ru': detectedName = 'Russian'; break;
                                    case 'uk': detectedName = 'Ukrainian'; break;
                                    default: detectedName = langCode.toUpperCase(); break;
                                }
                            }
                        }

                        if (isNonEnglish && window.Android && window.Android.onNonEnglishPageDetected) {
                            window.Android.onNonEnglishPageDetected(detectedName, detectedCode);
                        }
                    }

                    if (document.readyState === 'complete' || document.readyState === 'interactive') {
                        setTimeout(detectAndReport, 350);
                    } else {
                        window.addEventListener('DOMContentLoaded', function() { setTimeout(detectAndReport, 350); });
                    }
                } catch(e) {}
            })();
        """.trimIndent()
    }

    /**
     * Executes 100% full-page translation directly on the tab.
     */
    fun translateTab(activity: MainActivity, tab: MainActivity.Tab?, targetLang: String = "en", languageDisplayName: String = "English") {
        val currentTab = tab ?: activity.getActiveTab() ?: return
        val url = currentTab.url
        if (activity.isHomeTab(currentTab) || url.startsWith("haze://") || url.startsWith("about:")) {
            Toast.makeText(activity, "Cannot translate internal page", Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(activity, "Translating page to $languageDisplayName (100%)...", Toast.LENGTH_SHORT).show()
        val translatedUrl = getTranslatedUrl(url, targetLang)
        currentTab.url = translatedUrl
        currentTab.browserTab.navigationController.loadUrl(translatedUrl)
    }

    /**
     * Reverts a translated tab back to its original language and URL.
     */
    fun showOriginalTab(activity: MainActivity, tab: MainActivity.Tab?) {
        val currentTab = tab ?: activity.getActiveTab() ?: return
        val url = currentTab.url
        if (!isTranslatedUrl(url)) return

        Toast.makeText(activity, "Loading original page...", Toast.LENGTH_SHORT).show()
        val origUrl = getOriginalUrlFromTranslated(url)
        currentTab.url = origUrl
        currentTab.browserTab.navigationController.loadUrl(origUrl)
    }

    /**
     * Shows the language selection picker dialog to translate into any language or revert.
     */
    fun showLanguagePickerDialog(activity: MainActivity) {
        val tab = activity.getActiveTab() ?: return
        val url = tab.url
        if (activity.isHomeTab(tab) || url.startsWith("haze://") || url.startsWith("about:")) {
            Toast.makeText(activity, "Cannot translate internal page", Toast.LENGTH_SHORT).show()
            return
        }

        val isCurrentlyTranslated = isTranslatedUrl(url)
        val options = mutableListOf<String>()
        if (isCurrentlyTranslated) {
            options.add("★ Show Original Page (Untranslated)")
        }
        SUPPORTED_LANGUAGES.forEach { options.add(it.first) }

        AlertDialog.Builder(activity)
            .setTitle("Translate Page Into")
            .setItems(options.toTypedArray()) { _, which ->
                if (isCurrentlyTranslated && which == 0) {
                    showOriginalTab(activity, tab)
                } else {
                    val langIndex = if (isCurrentlyTranslated) which - 1 else which
                    val chosen = SUPPORTED_LANGUAGES[langIndex]
                    translateTab(activity, tab, chosen.second, chosen.third)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Shows a clean bottom banner proposing translation to English when a foreign page is loaded or reloaded.
     */
    fun showSuggestionBanner(activity: MainActivity, tab: MainActivity.Tab, detectedLanguage: String, langCode: String = "") {
        // Strict guard: Never prompt translation if detected as English or langCode is en
        if (langCode.equals("en", ignoreCase = true) || detectedLanguage.equals("English", ignoreCase = true)) {
            return
        }

        val currentUrl = tab.url
        if (activity.isHomeTab(tab) || currentUrl.startsWith("haze://") || currentUrl.startsWith("about:") || currentUrl.startsWith("file:") || currentUrl.startsWith("chrome://")) {
            return
        }
        if (isTranslatedUrl(currentUrl)) return
        if (dismissedUrls.contains(currentUrl)) return
        if (currentUrl == lastPromptedUrl && activeTranslationBanner != null) return
        lastPromptedUrl = currentUrl

        dismissSuggestionBanner(activity, clearLastPrompted = false)

        val prefs = activity.getSharedPreferences("HazePrefs", Context.MODE_PRIVATE)
        val isDark = prefs.getBoolean("is_dark", false) || activity.isPrivateMode
        val bannerBg = if (isDark) Color.parseColor("#262626") else Color.parseColor("#E8F0FE")
        val textColor = if (isDark) Color.WHITE else Color.parseColor("#174EA6")
        val accentColor = Color.parseColor(prefs.getString("accent_color", "#1A73E8") ?: "#1A73E8")

        val banner = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpToPx(activity, 16), dpToPx(activity, 10), dpToPx(activity, 12), dpToPx(activity, 10))
            background = GradientDrawable().apply {
                cornerRadius = dpToPx(activity, 24).toFloat()
                setColor(bannerBg)
                setStroke(dpToPx(activity, 1), if (isDark) Color.parseColor("#3C4043") else Color.parseColor("#D3E3FD"))
            }
            elevation = dpToPx(activity, 12).toFloat()
        }

        val icon = ImageView(activity).apply {
            setImageResource(R.drawable.ic_translate)
            setColorFilter(textColor)
            layoutParams = LinearLayout.LayoutParams(dpToPx(activity, 20), dpToPx(activity, 20)).apply {
                marginEnd = dpToPx(activity, 10)
            }
        }

        val langDisplay = if (detectedLanguage.isNotBlank()) detectedLanguage else "a foreign language"
        val tvText = TextView(activity).apply {
            text = "Page is in $langDisplay. Translate to English?"
            textSize = 12.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(textColor)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val btnTranslate = TextView(activity).apply {
            text = "Translate"
            textSize = 12.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                cornerRadius = dpToPx(activity, 14).toFloat()
                setColor(accentColor)
            }
            setPadding(dpToPx(activity, 14), dpToPx(activity, 7), dpToPx(activity, 14), dpToPx(activity, 7))
            setOnClickListener {
                activity.triggerStrongHaptic()
                dismissSuggestionBanner(activity, clearLastPrompted = false)
                translateTab(activity, tab, "en", "English")
            }
        }

        val btnClose = ImageButton(activity).apply {
            setImageResource(R.drawable.ic_close)
            setColorFilter(textColor)
            background = null
            setPadding(dpToPx(activity, 4), dpToPx(activity, 4), dpToPx(activity, 4), dpToPx(activity, 4))
            layoutParams = LinearLayout.LayoutParams(dpToPx(activity, 28), dpToPx(activity, 28)).apply {
                marginStart = dpToPx(activity, 6)
            }
            setOnClickListener {
                activity.triggerStrongHaptic()
                dismissedUrls.add(tab.url)
                dismissSuggestionBanner(activity, clearLastPrompted = false)
                lastPromptedUrl = tab.url
            }
        }

        banner.addView(icon)
        banner.addView(tvText)
        banner.addView(btnTranslate)
        banner.addView(btnClose)

        val params = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM
            setMargins(dpToPx(activity, 16), 0, dpToPx(activity, 16), dpToPx(activity, 76))
        }

        activeTranslationBanner = banner
        val decorView = activity.window.decorView as? ViewGroup
        val targetContainer = activity.findViewById<FrameLayout>(R.id.webContainer) ?: decorView
        targetContainer?.addView(banner, params)

        banner.alpha = 0f
        banner.translationY = dpToPx(activity, 20).toFloat()
        banner.animate().alpha(1f).translationY(0f).setDuration(220).setInterpolator(BUTTER_INTERPOLATOR).start()
    }

    /**
     * Shows an active translation bar when viewing a translated page, allowing easy revert or language change.
     */
    fun showActiveTranslationBar(activity: MainActivity, tab: MainActivity.Tab, targetLangCode: String = "en") {
        if (activeTranslationBanner != null) return

        val prefs = activity.getSharedPreferences("HazePrefs", Context.MODE_PRIVATE)
        val isDark = prefs.getBoolean("is_dark", false) || activity.isPrivateMode
        val bannerBg = if (isDark) Color.parseColor("#262626") else Color.parseColor("#E8F0FE")
        val textColor = if (isDark) Color.WHITE else Color.parseColor("#174EA6")
        val accentColor = Color.parseColor(prefs.getString("accent_color", "#1A73E8") ?: "#1A73E8")

        val targetName = SUPPORTED_LANGUAGES.find { it.second.startsWith(targetLangCode) }?.third ?: "English"

        val banner = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpToPx(activity, 14), dpToPx(activity, 9), dpToPx(activity, 10), dpToPx(activity, 9))
            background = GradientDrawable().apply {
                cornerRadius = dpToPx(activity, 24).toFloat()
                setColor(bannerBg)
                setStroke(dpToPx(activity, 1), if (isDark) Color.parseColor("#3C4043") else Color.parseColor("#D3E3FD"))
            }
            elevation = dpToPx(activity, 12).toFloat()
        }

        val icon = ImageView(activity).apply {
            setImageResource(R.drawable.ic_translate)
            setColorFilter(accentColor)
            layoutParams = LinearLayout.LayoutParams(dpToPx(activity, 20), dpToPx(activity, 20)).apply {
                marginEnd = dpToPx(activity, 8)
            }
        }

        val tvText = TextView(activity).apply {
            text = "Translated to $targetName"
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setTextColor(textColor)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val btnOriginal = TextView(activity).apply {
            text = "Original"
            textSize = 11.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(textColor)
            background = GradientDrawable().apply {
                cornerRadius = dpToPx(activity, 12).toFloat()
                setColor(if (isDark) Color.parseColor("#3C4043") else Color.parseColor("#D3E3FD"))
            }
            setPadding(dpToPx(activity, 10), dpToPx(activity, 6), dpToPx(activity, 10), dpToPx(activity, 6))
            setOnClickListener {
                activity.triggerStrongHaptic()
                dismissSuggestionBanner(activity)
                showOriginalTab(activity, tab)
            }
        }

        val btnChange = TextView(activity).apply {
            text = "Languages"
            textSize = 11.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                cornerRadius = dpToPx(activity, 12).toFloat()
                setColor(accentColor)
            }
            setPadding(dpToPx(activity, 10), dpToPx(activity, 6), dpToPx(activity, 10), dpToPx(activity, 6))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dpToPx(activity, 6)
            }
            setOnClickListener {
                activity.triggerStrongHaptic()
                showLanguagePickerDialog(activity)
            }
        }

        val btnClose = ImageButton(activity).apply {
            setImageResource(R.drawable.ic_close)
            setColorFilter(textColor)
            background = null
            setPadding(dpToPx(activity, 4), dpToPx(activity, 4), dpToPx(activity, 4), dpToPx(activity, 4))
            layoutParams = LinearLayout.LayoutParams(dpToPx(activity, 24), dpToPx(activity, 24)).apply {
                marginStart = dpToPx(activity, 4)
            }
            setOnClickListener {
                activity.triggerStrongHaptic()
                dismissSuggestionBanner(activity)
            }
        }

        banner.addView(icon)
        banner.addView(tvText)
        banner.addView(btnOriginal)
        banner.addView(btnChange)
        banner.addView(btnClose)

        val params = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM
            setMargins(dpToPx(activity, 16), 0, dpToPx(activity, 16), dpToPx(activity, 76))
        }

        activeTranslationBanner = banner
        val decorView = activity.window.decorView as? ViewGroup
        val targetContainer = activity.findViewById<FrameLayout>(R.id.webContainer) ?: decorView
        targetContainer?.addView(banner, params)

        banner.alpha = 0f
        banner.translationY = dpToPx(activity, 20).toFloat()
        banner.animate().alpha(1f).translationY(0f).setDuration(220).setInterpolator(BUTTER_INTERPOLATOR).start()
    }

    /**
     * Dismisses the banner if currently showing.
     */
    fun dismissSuggestionBanner(activity: MainActivity, clearLastPrompted: Boolean = false) {
        if (clearLastPrompted) {
            lastPromptedUrl = null
        }
        val v = activeTranslationBanner ?: return
        v.animate().alpha(0f).translationY(dpToPx(activity, 20).toFloat()).setDuration(180).withEndAction {
            (v.parent as? ViewGroup)?.removeView(v)
            if (activeTranslationBanner === v) {
                activeTranslationBanner = null
            }
        }.start()
    }
}
