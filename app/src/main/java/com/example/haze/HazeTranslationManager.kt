package com.example.haze

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.PathInterpolator
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.example.haze.engine.IBrowserTab
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.Executors

/**
 * Robust in-page translation and language detection engine for Haze.
 * Translates any webpage in-place seamlessly without broken third-party wrappers,
 * and automatically detects foreign language pages (e.g., Chinese, Spanish, Japanese,
 * Russian, French, German, Arabic, Hindi, etc.) and suggests translating to English upon load/reload.
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
    private val translationExecutor = Executors.newFixedThreadPool(4)

    private fun dpToPx(context: Context, dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            context.resources.displayMetrics
        ).toInt()
    }

    /**
     * Translates a single string via Google's translation API endpoint directly from Android native network layer.
     * Bypasses webpage Content-Security-Policy and CORS restrictions completely.
     */
    fun translateSingleText(text: String, targetLang: String): String {
        val trimmed = text.trim()
        if (trimmed.length < 2) return text
        var conn: HttpURLConnection? = null
        return try {
            val encoded = URLEncoder.encode(trimmed, "UTF-8")
            val url = URL("https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=$targetLang&dt=t&q=$encoded")
            conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 4000
                readTimeout = 4000
                setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            }
            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                val response = conn.inputStream.bufferedReader().use { it.readText() }
                val root = JSONArray(response)
                val segments = root.optJSONArray(0)
                if (segments != null) {
                    val sb = StringBuilder()
                    for (i in 0 until segments.length()) {
                        val seg = segments.optJSONArray(i)
                        if (seg != null) {
                            val part = seg.optString(0)
                            if (!part.isNullOrEmpty()) sb.append(part)
                        }
                    }
                    if (sb.isNotEmpty()) {
                        // Preserve leading/trailing whitespace of original
                        val leadingSpace = text.takeWhile { it.isWhitespace() }
                        val trailingSpace = text.takeLastWhile { it.isWhitespace() }
                        "$leadingSpace$sb$trailingSpace"
                    } else {
                        text
                    }
                } else {
                    text
                }
            } else {
                text
            }
        } catch (_: Exception) {
            text
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * Handles batch translation requests received from the injected page script.
     */
    fun handleNativeTranslationBatch(
        activity: MainActivity,
        ownerTab: IBrowserTab?,
        ownerWebView: WebView?,
        startIndex: Int,
        batchJson: String,
        targetLang: String
    ) {
        translationExecutor.execute {
            try {
                val arr = JSONArray(batchJson)
                val translatedArr = JSONArray()
                for (i in 0 until arr.length()) {
                    val orig = arr.optString(i, "")
                    if (orig.isBlank()) {
                        translatedArr.put(orig)
                    } else {
                        val translated = translateSingleText(orig, targetLang)
                        translatedArr.put(translated)
                    }
                }
                activity.runOnUiThread {
                    val script = "if (window.__hazeApplyBatch) { window.__hazeApplyBatch($startIndex, ${translatedArr}); }"
                    if (ownerTab != null) {
                        ownerTab.evaluateJavascript(script, null)
                    } else if (ownerWebView != null) {
                        ownerWebView.evaluateJavascript(script, null)
                    } else {
                        activity.getActiveTab()?.browserTab?.evaluateJavascript(script, null)
                    }
                }
            } catch (_: Exception) {}
        }
    }

    /**
     * Checks if a page is in a foreign language and notifies the Android interface.
     */
    fun getLanguageDetectionScript(): String {
        return """
            (function() {
                try {
                    function detectAndReport() {
                        var html = document.documentElement;
                        var rawLang = (html.getAttribute('lang') || document.body.getAttribute('lang') || '').trim().toLowerCase();
                        
                        // Parse language code
                        var langCode = rawLang.split(/[-_]/)[0];
                        
                        // Check meta tags as fallback
                        if (!langCode) {
                            var metaLang = document.querySelector('meta[http-equiv="content-language"], meta[name="language"], meta[property="og:locale"]');
                            if (metaLang) {
                                var c = (metaLang.getAttribute('content') || '').toLowerCase().trim();
                                if (c) langCode = c.split(/[-_]/)[0];
                            }
                        }

                        // Inspect actual visible text sample
                        var sampleText = '';
                        try {
                            var walker = document.createTreeWalker(document.body || html, NodeFilter.SHOW_TEXT, null, false);
                            var node, count = 0;
                            while ((node = walker.nextNode()) && count < 2500) {
                                var val = (node.nodeValue || '').trim();
                                if (val.length > 2 && node.parentElement && !['SCRIPT','STYLE','NOSCRIPT'].includes(node.parentElement.tagName)) {
                                    sampleText += ' ' + val;
                                    count += val.length;
                                }
                            }
                        } catch(e) {}

                        // Script / character frequency analysis
                        var isNonEnglish = false;
                        var detectedName = '';
                        var detectedCode = '';

                        // Chinese (Hanzi)
                        if (/[\u4e00-\u9fa5]/.test(sampleText)) {
                            isNonEnglish = true;
                            detectedName = 'Chinese';
                            detectedCode = 'zh-CN';
                        }
                        // Japanese (Hiragana / Katakana)
                        else if (/[\u3040-\u309f\u30a0-\u30ff]/.test(sampleText)) {
                            isNonEnglish = true;
                            detectedName = 'Japanese';
                            detectedCode = 'ja';
                        }
                        // Korean (Hangul)
                        else if (/[\uac00-\ud7af]/.test(sampleText)) {
                            isNonEnglish = true;
                            detectedName = 'Korean';
                            detectedCode = 'ko';
                        }
                        // Cyrillic (Russian, Ukrainian, etc.)
                        else if (/[\u0400-\u04ff]/.test(sampleText)) {
                            isNonEnglish = true;
                            detectedName = 'Russian';
                            detectedCode = 'ru';
                        }
                        // Arabic
                        else if (/[\u0600-\u06ff]/.test(sampleText)) {
                            isNonEnglish = true;
                            detectedName = 'Arabic';
                            detectedCode = 'ar';
                        }
                        // Devanagari (Hindi)
                        else if (/[\u0900-\u097f]/.test(sampleText)) {
                            isNonEnglish = true;
                            detectedName = 'Hindi';
                            detectedCode = 'hi';
                        }
                        // Thai
                        else if (/[\u0e00-\u0e7f]/.test(sampleText)) {
                            isNonEnglish = true;
                            detectedName = 'Thai';
                            detectedCode = 'th';
                        }
                        // Bengali
                        else if (/[\u0980-\u09ff]/.test(sampleText)) {
                            isNonEnglish = true;
                            detectedName = 'Bengali';
                            detectedCode = 'bn';
                        }
                        // Latin-based non-English via HTML lang attribute
                        else if (langCode && langCode !== 'en') {
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
                                default: detectedName = langCode.toUpperCase(); break;
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
     * Complete in-page client translation injection.
     * Uses client translate element + dynamic multi-chunk in-place text replacement engine
     * with native Android bridge for 100% reliability on any webpage (including strict CSP sites).
     */
    fun buildTranslationScript(targetLang: String = "en"): String {
        return """
            (function() {
                try {
                    window.__hazeActiveTargetLang = '$targetLang';
                    
                    // 1. Check if Google Translate Element already initialized
                    var combo = document.querySelector('.goog-te-combo');
                    if (combo) {
                        combo.value = '$targetLang';
                        combo.dispatchEvent(new Event('change', { bubbles: true }));
                        return;
                    }

                    // 2. Hide unwanted Google Translate banners & overlays cleanly
                    if (!document.getElementById('__haze_trans_style')) {
                        var style = document.createElement('style');
                        style.id = '__haze_trans_style';
                        style.textContent = [
                            '.goog-te-banner-frame, .goog-te-balloon-frame, #goog-gt-tt, .goog-tooltip { display: none !important; visibility: hidden !important; }',
                            'body { top: 0px !important; position: static !important; }',
                            '.skiptranslate { display: none !important; }',
                            '.goog-text-highlight { background: none !important; box-shadow: none !important; }'
                        ].join('\n');
                        (document.head || document.documentElement).appendChild(style);
                    }

                    // 3. Mount Google Translate Element container
                    if (!document.getElementById('google_translate_element')) {
                        var gtDiv = document.createElement('div');
                        gtDiv.id = 'google_translate_element';
                        gtDiv.style.display = 'none';
                        (document.body || document.documentElement).appendChild(gtDiv);
                    }

                    // 4. Set cookie fallback for Google Translate engine
                    try {
                        var domain = window.location.hostname;
                        document.cookie = 'googtrans=/auto/$targetLang; path=/; domain=' + domain;
                        document.cookie = 'googtrans=/auto/$targetLang; path=/;';
                    } catch(e) {}

                    // 5. Direct DOM multi-chunk translation engine (native bridge supported)
                    window.fallbackTranslatePage = function(target) {
                        try {
                            if (window.__hazeTranslating) return;
                            window.__hazeTranslating = true;

                            var walker = document.createTreeWalker(
                                document.body || document.documentElement,
                                NodeFilter.SHOW_TEXT,
                                null,
                                false
                            );
                            window.__hazeTransNodes = [];
                            var node;
                            while (node = walker.nextNode()) {
                                var val = (node.nodeValue || '').trim();
                                if (val.length > 1 && node.parentElement && 
                                    !['SCRIPT','STYLE','NOSCRIPT','CODE','PRE','TEXTAREA','INPUT'].includes(node.parentElement.tagName)) {
                                    window.__hazeTransNodes.push(node);
                                }
                            }

                            var total = window.__hazeTransNodes.length;
                            if (total === 0) return;

                            var batchSize = 12;
                            var batchIndex = 0;

                            window.__hazeApplyBatch = function(startIndex, transArray) {
                                try {
                                    for (var i = 0; i < transArray.length; i++) {
                                        var nIdx = startIndex + i;
                                        if (nIdx < window.__hazeTransNodes.length && transArray[i]) {
                                            window.__hazeTransNodes[nIdx].nodeValue = transArray[i];
                                        }
                                    }
                                } catch(e) {}
                            };

                            function sendBatch() {
                                if (batchIndex >= total) return;
                                var currentStart = batchIndex;
                                var chunk = [];
                                for (var i = currentStart; i < Math.min(currentStart + batchSize, total); i++) {
                                    chunk.push(window.__hazeTransNodes[i].nodeValue || '');
                                }
                                batchIndex += batchSize;

                                if (window.Android && window.Android.translateBatchNative) {
                                    window.Android.translateBatchNative(currentStart, JSON.stringify(chunk), target);
                                    setTimeout(sendBatch, 50);
                                } else {
                                    chunk.forEach(function(origText, offset) {
                                        var trimmed = origText.trim();
                                        if (trimmed.length < 2) return;
                                        var apiUrl = 'https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=' + target + '&dt=t&q=' + encodeURIComponent(trimmed);
                                        fetch(apiUrl)
                                            .then(function(res) { return res.json(); })
                                            .then(function(data) {
                                                if (data && data[0]) {
                                                    var full = '';
                                                    for (var s = 0; s < data[0].length; s++) {
                                                        if (data[0][s] && data[0][s][0]) full += data[0][s][0];
                                                    }
                                                    if (full && window.__hazeTransNodes[currentStart + offset]) {
                                                        window.__hazeTransNodes[currentStart + offset].nodeValue = full;
                                                    }
                                                }
                                            })
                                            .catch(function() {});
                                    });
                                    setTimeout(sendBatch, 80);
                                }
                            }

                            sendBatch();
                        } catch(e) {}
                    };

                    // 6. Setup initialization hook
                    window.googleTranslateElementInit = function() {
                        try {
                            new google.translate.TranslateElement({
                                pageLanguage: 'auto',
                                layout: google.translate.TranslateElement.InlineLayout.SIMPLE,
                                autoDisplay: false
                            }, 'google_translate_element');

                            var tries = 0;
                            var timer = setInterval(function() {
                                var sel = document.querySelector('.goog-te-combo');
                                if (sel) {
                                    clearInterval(timer);
                                    sel.value = '$targetLang';
                                    sel.dispatchEvent(new Event('change', { bubbles: true }));
                                } else if (++tries > 15) {
                                    clearInterval(timer);
                                    fallbackTranslatePage('$targetLang');
                                }
                            }, 150);
                        } catch(err) {
                            fallbackTranslatePage('$targetLang');
                        }
                    };

                    // Load Google Translate JS library
                    var script = document.createElement('script');
                    script.src = 'https://translate.google.com/translate_a/element.js?cb=googleTranslateElementInit';
                    script.onerror = function() {
                        fallbackTranslatePage('$targetLang');
                    };
                    (document.head || document.documentElement).appendChild(script);

                    // Re-trigger fallback if element script fails to execute within 2 seconds
                    setTimeout(function() {
                        var sel = document.querySelector('.goog-te-combo');
                        if (!sel) {
                            fallbackTranslatePage('$targetLang');
                        }
                    }, 2000);

                } catch(e) {
                    if (window.fallbackTranslatePage) window.fallbackTranslatePage('$targetLang');
                }
            })();
        """.trimIndent()
    }

    /**
     * Executes in-page translation directly on the tab.
     */
    fun translateTab(activity: MainActivity, tab: MainActivity.Tab?, targetLang: String = "en", languageDisplayName: String = "English") {
        val currentTab = tab ?: activity.getActiveTab() ?: return
        val url = currentTab.url
        if (activity.isHomeTab(currentTab) || url.startsWith("haze://") || url.startsWith("about:")) {
            Toast.makeText(activity, "Cannot translate internal page", Toast.LENGTH_SHORT).show()
            return
        }

        Toast.makeText(activity, "Translating page to $languageDisplayName...", Toast.LENGTH_SHORT).show()

        val script = buildTranslationScript(targetLang)
        currentTab.browserTab.evaluateJavascript(script, null)
    }

    /**
     * Shows the language selection picker dialog to translate into any language.
     */
    fun showLanguagePickerDialog(activity: MainActivity) {
        val tab = activity.getActiveTab() ?: return
        val url = tab.url
        if (activity.isHomeTab(tab) || url.startsWith("haze://") || url.startsWith("about:")) {
            Toast.makeText(activity, "Cannot translate internal page", Toast.LENGTH_SHORT).show()
            return
        }

        val names = SUPPORTED_LANGUAGES.map { it.first }.toTypedArray()
        AlertDialog.Builder(activity)
            .setTitle("Translate Page Into")
            .setItems(names) { _, which ->
                val chosen = SUPPORTED_LANGUAGES[which]
                translateTab(activity, tab, chosen.second, chosen.third)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Shows a clean bottom banner proposing translation to English when a foreign page is loaded or reloaded.
     */
    fun showSuggestionBanner(activity: MainActivity, tab: MainActivity.Tab, detectedLanguage: String, langCode: String = "") {
        val currentUrl = tab.url
        if (currentUrl == lastPromptedUrl && activeTranslationBanner != null) {
            return
        }
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
