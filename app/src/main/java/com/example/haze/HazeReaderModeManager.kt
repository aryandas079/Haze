package com.example.haze

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.regex.Pattern

/**
 * Haze Immersive Flow Reader
 * Indigenous Reading Mode inspired by Microsoft Edge Immersive Reader.
 * Features:
 * - Clean Distraction-Free Article Layout with estimated reading time & word count
 * - 11 Tuned Color Themes (matching Edge palette from Cream to AMOLED)
 * - Custom Typography (Outfit, Sitka, Georgia, Merriweather, OpenDyslexic, Monospace)
 * - Dynamic Font Size, Column Width, and Line Spacing controls
 * - Integrated Read Aloud Player Bar with Sentence-by-Sentence Real-Time Highlighting
 * - Interactive Reading Progress Bar tracking scroll depth
 * - Dyslexia-friendly Line Focus mode
 * - One-tap in-reader translation
 */
class HazeReaderModeManager(
    private val activity: MainActivity,
    private val prefs: SharedPreferences
) {
    private var tts: TextToSpeech? = null
    private var isTtsSpeaking = false
    private var ttsSpeed = 1.0f
    private var sentences: List<String> = emptyList()
    private var currentSentenceIndex = 0
    private var readerDialog: Dialog? = null
    private var activeWebView: WebView? = null
    private var googleTtsEngine: String? = null
    private var currentTranslateAction: ((String, String) -> Unit)? = null
    private var currentRevertAction: (() -> Unit)? = null
    private var currentVoiceAction: (() -> Unit)? = null
    private var selectedVoice: Voice? = null
    private var selectedVoiceName: String?
        get() = prefs.getString("reader_voice_name", null)
        set(value) = prefs.edit().putString("reader_voice_name", value).apply()

    fun translateToLanguage(targetCode: String, targetName: String = "") {
        val lang = HazeTranslationManager.SUPPORTED_LANGUAGES.find { it.second.equals(targetCode, ignoreCase = true) }
        val name = if (targetName.isNotEmpty()) targetName else (lang?.first ?: targetCode)
        currentTranslateAction?.invoke(targetCode, name)
    }

    fun revertTranslation() {
        currentRevertAction?.invoke()
    }

    fun showVoiceOptions() {
        currentVoiceAction?.invoke()
    }

    // Appearance State (Persisted)
    private var currentThemeIndex: Int
        get() = prefs.getInt("reader_theme_index", 0)
        set(value) = prefs.edit().putInt("reader_theme_index", value).apply()

    private var currentFontName: String
        get() = prefs.getString("reader_font_name", "Outfit") ?: "Outfit"
        set(value) = prefs.edit().putString("reader_font_name", value).apply()

    private var currentFontSizePx: Int
        get() = prefs.getInt("reader_font_size", 18)
        set(value) = prefs.edit().putInt("reader_font_size", value).apply()

    private var currentColumnWidthProgress: Int
        get() = prefs.getInt("reader_column_width", 2) // 0 to 4
        set(value) = prefs.edit().putInt("reader_column_width", value).apply()

    private var currentLineSpacingProgress: Int
        get() = prefs.getInt("reader_line_spacing", 2) // 0 to 3
        set(value) = prefs.edit().putInt("reader_line_spacing", value).apply()

    private var isLineFocusEnabled: Boolean
        get() = prefs.getBoolean("reader_line_focus", false)
        set(value) = prefs.edit().putBoolean("reader_line_focus", value).apply()

    // 11 Themes matching Microsoft Reading Mode
    data class ReaderTheme(
        val name: String,
        val bg: String,
        val text: String,
        val accent: String,
        val highlightBg: String
    )

    val themes = listOf(
        ReaderTheme("Parchment", "#FBF0D9", "#3A2A1A", "#8B4513", "rgba(139, 69, 19, 0.22)"),
        ReaderTheme("White", "#FFFFFF", "#1F2328", "#0969DA", "rgba(9, 105, 218, 0.20)"),
        ReaderTheme("Mist Grey", "#EAECEE", "#2C3E50", "#2980B9", "rgba(41, 128, 185, 0.22)"),
        ReaderTheme("Calm Cyan", "#E0F7FA", "#004D40", "#00897B", "rgba(0, 137, 123, 0.22)"),
        ReaderTheme("Warm Canary", "#FFFDE7", "#3E2723", "#F57F17", "rgba(245, 127, 23, 0.25)"),
        ReaderTheme("Pastel Mint", "#E8F5E9", "#1B5E20", "#2E7D32", "rgba(46, 125, 50, 0.22)"),
        ReaderTheme("Lavender", "#EDE7F6", "#311B92", "#5E35B1", "rgba(94, 53, 177, 0.22)"),
        ReaderTheme("Sky Blue", "#E3F2FD", "#0D47A1", "#1976D2", "rgba(25, 118, 210, 0.22)"),
        ReaderTheme("Sage Forest", "#DCEDC8", "#33691E", "#558B2F", "rgba(85, 139, 47, 0.24)"),
        ReaderTheme("Dark Charcoal", "#1E1F22", "#E3E5E8", "#58A6FF", "rgba(88, 166, 255, 0.25)"),
        ReaderTheme("AMOLED Midnight", "#000000", "#F5F5F5", "#2F81F7", "rgba(47, 129, 247, 0.30)")
    )

    private fun dpToPx(dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            activity.resources.displayMetrics
        ).toInt()
    }

    private fun getSelectableRes(): Int {
        val outValue = TypedValue()
        activity.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, outValue, true)
        return outValue.resourceId
    }

    /**
     * Entry point to launch Reading Mode on current active web page
     */
    fun openReaderMode() {
        val tab = activity.getActiveTab() ?: return
        val pageHost = try {
            android.net.Uri.parse(tab.url).host?.lowercase()
        } catch (_: Exception) { null }

        if (pageHost == "youtube.com" || pageHost?.endsWith(".youtube.com") == true ||
            pageHost == "youtube-nocookie.com" || pageHost?.endsWith(".youtube-nocookie.com") == true ||
            pageHost == "netflix.com" || pageHost?.endsWith(".netflix.com") == true ||
            pageHost == "twitch.tv" || pageHost?.endsWith(".twitch.tv") == true
        ) {
            Toast.makeText(activity, "Reading mode is not available for video and streaming pages.", Toast.LENGTH_LONG).show()
            return
        }

        val extractionScript = """
            (function() {
                var selectors = ['article', 'main', '[role="main"]', '.post-content', '.article-content', '.entry-content', '#content', '.mw-parser-output'];
                var article = null;
                var bestLength = 0;
                for (var i = 0; i < selectors.length; i++) {
                    var candidates = document.querySelectorAll(selectors[i]);
                    for (var j = 0; j < candidates.length; j++) {
                        var candidate = candidates[j];
                        var length = (candidate.innerText || candidate.textContent || '').replace(/\s+/g, ' ').trim().length;
                        if (length > bestLength) {
                            bestLength = length;
                            article = candidate;
                        }
                    }
                }
                if (!article) article = document.body;
                var copy = article.cloneNode(true);

                // Expand details & collapsed elements
                var detailsEls = copy.querySelectorAll('details');
                for (var d = 0; d < detailsEls.length; d++) { detailsEls[d].open = true; }

                var collapsed = copy.querySelectorAll('[aria-expanded="false"], [aria-hidden="true"], .collapse, .collapsed, .hidden, [hidden], .mw-collapsed');
                for (var c = 0; c < collapsed.length; c++) {
                    var el = collapsed[c];
                    el.removeAttribute('hidden');
                    el.removeAttribute('aria-hidden');
                    el.classList.remove('collapse', 'collapsed', 'hidden', 'mw-collapsed');
                    el.style.display = 'block';
                }

                // Remove redundant in-content h1 (already displayed as primary title)
                var innerH1s = copy.querySelectorAll('h1');
                for (var ih = 0; ih < innerH1s.length; ih++) { innerH1s[ih].remove(); }

                // Remove unwanted scripts, ads, trackers, sidebars, navbars, and jump links
                var unwanted = copy.querySelectorAll('script, style, noscript, iframe, video, audio, canvas, nav, footer, form, aside, .ad, .ads, .advertisement, .share-buttons, .social-share, .cookie-banner, .vector-menu, .navigation-not-searchable, .navbox, .vertical-navbox, .sitelist, .mw-editsection, .noprint, .mw-jump-link, .header-container, #mw-navigation, .toc, .mw-toc, .mw-indicators, .page-actions-menu, .pre-content, .minerva-header');
                for (var k = 0; k < unwanted.length; k++) { unwanted[k].remove(); }

                // Remove Wikipedia / CMS header nav lists (Article / Talk tabs)
                var allUls = copy.querySelectorAll('ul, ol');
                for (var u = 0; u < allUls.length; u++) {
                    var ulText = (allUls[u].innerText || '').trim();
                    if (ulText === 'Article\nTalk' || ulText === 'Article Talk' || ulText.startsWith('Article\nTalk')) {
                        allUls[u].remove();
                    }
                }

                // Strip inline styles so colors/opacity don't override the theme
                var allStyled = copy.querySelectorAll('[style]');
                for (var st = 0; st < allStyled.length; st++) {
                    if (allStyled[st].tagName.toLowerCase() !== 'img') {
                        allStyled[st].removeAttribute('style');
                    }
                }

                var plain = (copy.innerText || copy.textContent || '').replace(/\s+/g, ' ').trim();
                var words = plain.split(/\s+/).filter(Boolean);
                var wordCount = words.length;
                var readTimeMinutes = Math.max(1, Math.ceil(wordCount / 200));

                var title = document.title || 'Reader View';
                var h1 = document.querySelector('h1');
                if (h1 && h1.innerText.trim().length > 3) {
                    title = h1.innerText.trim();
                }

                var author = '';
                var authorMeta = document.querySelector('meta[name="author"], meta[property="article:author"], meta[name="twitter:creator"]');
                if (authorMeta) author = authorMeta.content || '';
                if (!author) {
                    var authorEl = document.querySelector('.author, .byline, [rel="author"], .by-line');
                    if (authorEl) author = authorEl.innerText.trim();
                }

                // Table of Contents extraction
                var headings = [];
                var hNodes = copy.querySelectorAll('h2, h3');
                for (var h = 0; h < hNodes.length; h++) {
                    var hText = hNodes[h].innerText.trim();
                    if (hText.length > 2 && hText.length < 80) {
                        var hId = 'haze-h-' + h;
                        hNodes[h].id = hId;
                        headings.push({ id: hId, text: hText, level: hNodes[h].tagName.toLowerCase() });
                    }
                }

                // Sentence Tagging for interactive TTS highlighting
                var pNodes = copy.querySelectorAll('p, li, blockquote');
                var sentenceIdx = 0;
                var sentencesList = [];
                for (var p = 0; p < pNodes.length; p++) {
                    var pEl = pNodes[p];
                    var rawText = pEl.innerText || '';
                    if (rawText.trim().length < 5) continue;

                    // Regex split into sentences with punctuation preserved
                    var parts = rawText.match(/[^.!?]+[.!?]+(\s+|$)|[^.!?]+$/g);
                    if (parts && parts.length > 0) {
                        var newHtml = '';
                        for (var s = 0; s < parts.length; s++) {
                            var sText = parts[s].trim();
                            if (sText.length > 1) {
                                newHtml += '<span id="haze-s-' + sentenceIdx + '" class="haze-sentence">' + parts[s] + '</span>';
                                sentencesList.push(sText);
                                sentenceIdx++;
                            } else {
                                newHtml += parts[s];
                            }
                        }
                        pEl.innerHTML = newHtml;
                    }
                }

                var paragraphs = copy.querySelectorAll('p').length;
                var readable = plain.length >= 250 && (paragraphs >= 1 || plain.length >= 500);

                return JSON.stringify({
                    title: title,
                    author: author,
                    readTime: readTimeMinutes + ' min read',
                    wordCount: wordCount.toLocaleString() + ' words',
                    html: copy.innerHTML,
                    plainText: plain,
                    sentences: sentencesList,
                    headings: headings,
                    readable: readable
                });
            })();
        """.trimIndent()

        tab.browserTab.evaluateJavascript(extractionScript) { result ->
            if (result == null) return@evaluateJavascript
            try {
                val cleanResult = if (result.startsWith("\"") && result.endsWith("\"")) {
                    org.json.JSONTokener(result).nextValue().toString()
                } else result
                val json = JSONObject(cleanResult)
                if (!json.optBoolean("readable", false)) {
                    Toast.makeText(activity, "This page does not contain enough readable article content.", Toast.LENGTH_LONG).show()
                    return@evaluateJavascript
                }

                val title = json.optString("title", "Reader Mode")
                val author = json.optString("author", "")
                val readTime = json.optString("readTime", "3 min read")
                val wordCount = json.optString("wordCount", "")
                val contentHtml = json.optString("html", "")
                val plainText = json.optString("plainText", "")

                val sentenceArray = json.optJSONArray("sentences")
                val extractedSentences = mutableListOf<String>()
                if (sentenceArray != null) {
                    for (i in 0 until sentenceArray.length()) {
                        extractedSentences.add(sentenceArray.optString(i))
                    }
                }
                sentences = extractedSentences

                showReaderDialog(title, author, readTime, wordCount, contentHtml, plainText)
            } catch (e: Exception) {
                Toast.makeText(activity, "Could not format page for reading", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showReaderDialog(
        title: String,
        author: String,
        readTime: String,
        wordCount: String,
        contentHtml: String,
        plainText: String
    ) {
        val dialog = Dialog(activity, android.R.style.Theme_Light_NoTitleBar_Fullscreen)
        dialog.window?.setWindowAnimations(R.style.HazeDialogAnimation)
        readerDialog = dialog

        val originalSentences = sentences.toList()
        var currentDisplayTitle: String? = null
        var currentDisplayContentHtml: String? = null
        var currentActiveLangCode: String? = null
        var currentActiveLangName: String? = null
        var isTranslating = false

        val isDark = prefs.getBoolean("is_dark", false)
        val defaultAccent = Color.parseColor(prefs.getString("accent_color", "#0A56D0") ?: "#0A56D0")

        val root = RelativeLayout(activity).apply {
            setBackgroundColor(Color.parseColor(themes[currentThemeIndex].bg))
        }

        // --- 1. Top Bar Container (Header + Floating Read Aloud Bar + Progress Indicator) ---
        val topContainer = LinearLayout(activity).apply {
            id = View.generateViewId()
            layoutParams = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.MATCH_PARENT,
                RelativeLayout.LayoutParams.WRAP_CONTENT
            )
            orientation = LinearLayout.VERTICAL
            elevation = dpToPx(8).toFloat()
        }

        // Header Toolbar matching Microsoft Edge Reader Mode (Image 1 & 2)
        val header = LinearLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpToPx(16), dpToPx(12), dpToPx(16), dpToPx(10))
            setBackgroundColor(if (isDark) Color.parseColor("#1E1F22") else Color.WHITE)
        }

        val btnClose = ImageButton(activity).apply {
            setImageResource(R.drawable.ic_close)
            setColorFilter(if (isDark) Color.WHITE else Color.parseColor("#1C1B1F"))
            setBackgroundResource(getSelectableRes())
            layoutParams = LinearLayout.LayoutParams(dpToPx(38), dpToPx(38))
            contentDescription = "Exit Reader Mode"
            setOnClickListener {
                activity.triggerStrongHaptic(it)
                stopTts()
                dialog.dismiss()
            }
        }

        val tvTitle = TextView(activity).apply {
            text = "Reader Mode"
            textSize = 16f
            setTypeface(null, Typeface.BOLD)
            setTextColor(if (isDark) Color.WHITE else Color.parseColor("#1C1B1F"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dpToPx(12)
            }
        }

        // Action Buttons: Read Aloud, Translate, Appearance
        val btnReadAloud = ImageButton(activity).apply {
            setImageResource(R.drawable.ic_volume_up)
            setColorFilter(if (isDark) Color.WHITE else Color.parseColor("#1C1B1F"))
            setBackgroundResource(getSelectableRes())
            layoutParams = LinearLayout.LayoutParams(dpToPx(38), dpToPx(38)).apply { marginEnd = dpToPx(8) }
            contentDescription = "Read Aloud with Text Highlighting"
        }

        val btnTranslate = ImageButton(activity).apply {
            setImageResource(R.drawable.ic_translate)
            setColorFilter(if (isDark) Color.WHITE else Color.parseColor("#1C1B1F"))
            setBackgroundResource(getSelectableRes())
            layoutParams = LinearLayout.LayoutParams(dpToPx(38), dpToPx(38)).apply { marginEnd = dpToPx(8) }
            contentDescription = "Translate Article"
        }

        val btnAppearance = ImageButton(activity).apply {
            setImageResource(R.drawable.ic_palette)
            setColorFilter(if (isDark) Color.WHITE else Color.parseColor("#1C1B1F"))
            setBackgroundResource(getSelectableRes())
            layoutParams = LinearLayout.LayoutParams(dpToPx(38), dpToPx(38))
            contentDescription = "Text and Appearance Settings"
        }

        header.addView(btnClose)
        header.addView(tvTitle)
        header.addView(btnReadAloud)
        header.addView(btnTranslate)
        header.addView(btnAppearance)
        topContainer.addView(header)

        // Floating In-Reader Translation Progress Bar
        val translationBar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(dpToPx(16), dpToPx(8), dpToPx(16), dpToPx(8))
            setBackgroundColor(if (isDark) Color.parseColor("#27292D") else Color.parseColor("#E8F0FE"))
        }

        val translationSpinner = ProgressBar(activity, null, android.R.attr.progressBarStyleSmall).apply {
            indeterminateTintList = ColorStateList.valueOf(defaultAccent)
            layoutParams = LinearLayout.LayoutParams(dpToPx(18), dpToPx(18)).apply {
                marginEnd = dpToPx(10)
            }
        }

        val tvTranslationStatus = TextView(activity).apply {
            text = "Translating article..."
            textSize = 12.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(if (isDark) Color.WHITE else Color.parseColor("#174EA6"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        translationBar.addView(translationSpinner)
        translationBar.addView(tvTranslationStatus)
        topContainer.addView(translationBar)

        // --- 2. Floating Read Aloud Player Bar (Image 4 inspired) ---
        val readAloudBar = LinearLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            setPadding(dpToPx(16), dpToPx(8), dpToPx(16), dpToPx(8))
            setBackgroundColor(if (isDark) Color.parseColor("#27292D") else Color.parseColor("#F1F3F4"))
        }

        val tvReadAloudStatus = TextView(activity).apply {
            text = "Read Aloud"
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(if (isDark) Color.parseColor("#A8C7FA") else Color.parseColor("#0B57D0"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val btnPrevSentence = ImageButton(activity).apply {
            setImageResource(R.drawable.ic_skip_previous)
            setColorFilter(if (isDark) Color.WHITE else Color.parseColor("#1C1B1F"))
            setBackgroundResource(getSelectableRes())
            layoutParams = LinearLayout.LayoutParams(dpToPx(36), dpToPx(36)).apply { marginEnd = dpToPx(4) }
            contentDescription = "Previous Sentence"
        }

        val btnPlayPause = ImageButton(activity).apply {
            setImageResource(R.drawable.ic_play)
            setColorFilter(if (isDark) Color.WHITE else Color.parseColor("#1C1B1F"))
            setBackgroundResource(getSelectableRes())
            layoutParams = LinearLayout.LayoutParams(dpToPx(38), dpToPx(38)).apply { marginEnd = dpToPx(4) }
            contentDescription = "Play/Pause"
        }

        val btnNextSentence = ImageButton(activity).apply {
            setImageResource(R.drawable.ic_skip_next)
            setColorFilter(if (isDark) Color.WHITE else Color.parseColor("#1C1B1F"))
            setBackgroundResource(getSelectableRes())
            layoutParams = LinearLayout.LayoutParams(dpToPx(36), dpToPx(36)).apply { marginEnd = dpToPx(8) }
            contentDescription = "Next Sentence"
        }

        val btnSpeedPill = TextView(activity).apply {
            text = "1.0x"
            textSize = 12f
            setTypeface(null, Typeface.BOLD)
            setPadding(dpToPx(8), dpToPx(4), dpToPx(8), dpToPx(4))
            setTextColor(if (isDark) Color.WHITE else Color.parseColor("#1C1B1F"))
            background = GradientDrawable().apply {
                cornerRadius = dpToPx(12).toFloat()
                setColor(if (isDark) Color.parseColor("#3B3D42") else Color.parseColor("#E0E2E5"))
            }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = dpToPx(8)
            }
        }

        val btnVoiceOptions = ImageButton(activity).apply {
            setImageResource(R.drawable.ic_settings)
            setColorFilter(if (isDark) Color.WHITE else Color.parseColor("#1C1B1F"))
            setBackgroundResource(getSelectableRes())
            layoutParams = LinearLayout.LayoutParams(dpToPx(36), dpToPx(36))
            contentDescription = "Voice Options"
        }

        readAloudBar.addView(tvReadAloudStatus)
        readAloudBar.addView(btnPrevSentence)
        readAloudBar.addView(btnPlayPause)
        readAloudBar.addView(btnNextSentence)
        readAloudBar.addView(btnSpeedPill)
        readAloudBar.addView(btnVoiceOptions)
        topContainer.addView(readAloudBar)

        // Reading Progress Indicator (Thin sleek bar below header)
        val progressBar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progress = 0
            progressTintList = ColorStateList.valueOf(defaultAccent)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(3))
        }
        topContainer.addView(progressBar)

        root.addView(topContainer)

        // --- 3. Reader WebView ---
        val webView = WebView(activity).apply {
            layoutParams = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.MATCH_PARENT,
                RelativeLayout.LayoutParams.MATCH_PARENT
            ).apply {
                addRule(RelativeLayout.BELOW, topContainer.id)
            }
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            setBackgroundColor(Color.TRANSPARENT)
        }
        activeWebView = webView

        // Connect scroll depth to progress bar
        webView.addJavascriptInterface(object {
            @JavascriptInterface
            fun onScrollProgress(percent: Float) {
                activity.runOnUiThread {
                    progressBar.progress = (percent * 1000).toInt().coerceIn(0, 1000)
                }
            }
        }, "HazeReaderBridge")

        // Helper to render HTML with current theme and typography
        fun reloadContent() {
            val theme = themes[currentThemeIndex]
            val fontCss = when (currentFontName) {
                "Sitka" -> "'Sitka Text', serif"
                "Georgia" -> "'Georgia', serif"
                "Merriweather" -> "'Merriweather', serif"
                "OpenDyslexic" -> "'OpenDyslexic', sans-serif"
                "Monospace" -> "monospace"
                else -> "'Outfit', sans-serif"
            }

            val maxWidthCss = when (currentColumnWidthProgress) {
                0 -> "500px"
                1 -> "590px"
                2 -> "680px"
                3 -> "780px"
                else -> "92%"
            }

            val lineHeightCss = when (currentLineSpacingProgress) {
                0 -> "1.4"
                1 -> "1.65"
                2 -> "1.88"
                else -> "2.2"
            }

            val lineFocusCss = if (isLineFocusEnabled) {
                """
                body.line-focus.has-active-focus .haze-sentence:not(.haze-highlight) {
                    opacity: 0.35 !important;
                    transition: opacity 0.25s ease;
                }
                body.line-focus .haze-sentence.haze-highlight {
                    opacity: 1 !important;
                }
                """
            } else ""

            val authorHtml = if (author.isNotEmpty()) "<span class=\"author\">By $author</span> • " else ""
            val wordCountHtml = if (wordCount.isNotEmpty()) " • <span class=\"words\">$wordCount</span>" else ""

            val readerHtml = """
                <!DOCTYPE html>
                <html>
                <head>
                    <meta name="viewport" content="width=device-width, initial-scale=1">
                    <style>
                        @import url('https://fonts.googleapis.com/css2?family=Merriweather:ital,wght@0,300;0,400;0,700;1,300&family=Outfit:wght@300;400;500;600;700&family=Georgia&family=Sitka+Text&display=swap');
                        
                        * { box-sizing: border-box; }
                        body {
                            font-family: $fontCss;
                            line-height: $lineHeightCss;
                            font-size: ${currentFontSizePx}px;
                            padding: 32px 20px 120px 20px;
                            margin: 0 auto;
                            max-width: $maxWidthCss;
                            color: ${theme.text};
                            background: ${theme.bg};
                            -webkit-font-smoothing: antialiased;
                            transition: background-color 0.25s ease, color 0.25s ease;
                        }
                        .article-badge {
                            display: inline-block;
                            font-size: 11px;
                            font-weight: 800;
                            letter-spacing: 1.2px;
                            text-transform: uppercase;
                            padding: 5px 10px;
                            border-radius: 6px;
                            background: rgba(14, 116, 235, 0.12);
                            color: ${theme.accent};
                            margin-bottom: 14px;
                        }
                        h1 {
                            font-size: 1.6em;
                            line-height: 1.3;
                            margin-top: 0;
                            margin-bottom: 14px;
                            font-weight: 800;
                            color: ${theme.text};
                        }
                        .article-meta {
                            font-size: 0.85em;
                            opacity: 0.72;
                            margin-bottom: 22px;
                            display: flex;
                            align-items: center;
                            flex-wrap: wrap;
                            gap: 4px;
                        }
                        .divider {
                            border: none;
                            height: 1px;
                            background: rgba(128, 128, 128, 0.25);
                            margin-bottom: 28px;
                        }
                        h2, h3, h4 { color: ${theme.text}; margin-top: 32px; font-weight: 700; }
                        img { max-width: 100%; height: auto; border-radius: 12px; margin: 24px 0; display: block; }
                        p, li, blockquote, div { color: ${theme.text}; }
                        p { margin-bottom: 22px; }
                        a { color: ${theme.accent}; text-decoration: none; border-bottom: 1px dotted ${theme.accent}; }
                        blockquote {
                            border-left: 3px solid ${theme.accent};
                            padding-left: 16px;
                            margin: 24px 0;
                            opacity: 0.9;
                            font-style: italic;
                        }
                        .haze-sentence {
                            transition: background-color 0.2s ease, border-radius 0.2s ease;
                        }
                        .haze-highlight {
                            background-color: ${theme.highlightBg} !important;
                            border-radius: 4px;
                            padding: 2px 4px;
                            box-shadow: 0 0 0 2px ${theme.highlightBg};
                        }
                        $lineFocusCss
                    </style>
                </head>
                <body class="${if (isLineFocusEnabled) "line-focus" else ""}">
                    <div class="article-badge">ARTICLE</div>
                    <h1>${currentDisplayTitle ?: title}</h1>
                    <div class="article-meta">$authorHtml<span>$readTime</span>$wordCountHtml</div>
                    <hr class="divider"/>
                    <div id="haze-content">${currentDisplayContentHtml ?: contentHtml}</div>
                    <script>
                        window.onscroll = function() {
                            var totalHeight = document.documentElement.scrollHeight - document.documentElement.clientHeight;
                            if (totalHeight > 0) {
                                var progress = window.scrollY / totalHeight;
                                if (window.HazeReaderBridge) {
                                    window.HazeReaderBridge.onScrollProgress(progress);
                                }
                            }
                        };
                        window.hazeHighlightSentence = function(idx) {
                            document.body.classList.add('has-active-focus');
                            var old = document.querySelectorAll('.haze-highlight');
                            for (var i = 0; i < old.length; i++) { old[i].classList.remove('haze-highlight'); }
                            var el = document.getElementById('haze-s-' + idx);
                            if (el) {
                                el.classList.add('haze-highlight');
                                el.scrollIntoView({ behavior: 'smooth', block: 'center' });
                            }
                        };
                        var allSents = document.querySelectorAll('.haze-sentence');
                        for (var s = 0; s < allSents.length; s++) {
                            allSents[s].addEventListener('click', function(e) {
                                document.body.classList.add('has-active-focus');
                                var old = document.querySelectorAll('.haze-highlight');
                                for (var i = 0; i < old.length; i++) { old[i].classList.remove('haze-highlight'); }
                                this.classList.add('haze-highlight');
                            });
                        }
                        window.hazeGetTranslatableItems = function() {
                            var items = [];
                            var h1 = document.querySelector('h1');
                            if (h1 && h1.innerText.trim()) {
                                items.push({ id: '__title__', text: h1.innerText.trim() });
                            }
                            var headings = document.querySelectorAll('h2, h3, h4');
                            for (var h = 0; h < headings.length; h++) {
                                var hd = headings[h];
                                if (!hd.id) hd.id = 'haze-h-' + h;
                                var ht = hd.innerText.trim();
                                if (ht) items.push({ id: hd.id, text: ht });
                            }
                            var sents = document.querySelectorAll('.haze-sentence');
                            for (var s = 0; s < sents.length; s++) {
                                var el = sents[s];
                                var st = el.innerText.trim();
                                if (st) items.push({ id: el.id, text: st });
                            }
                            var others = document.querySelectorAll('figcaption, th, td, dt, dd');
                            for (var o = 0; o < others.length; o++) {
                                var oth = others[o];
                                if (!oth.querySelector('.haze-sentence')) {
                                    if (!oth.id) oth.id = 'haze-oth-' + o;
                                    var ot = oth.innerText.trim();
                                    if (ot && ot.length > 1) items.push({ id: oth.id, text: ot });
                                }
                            }
                            return JSON.stringify(items);
                        };
                        window.hazeApplyTranslations = function(mapOrJson) {
                            try {
                                var map = typeof mapOrJson === 'string' ? JSON.parse(mapOrJson) : mapOrJson;
                                if (map['__title__']) {
                                    var h1 = document.querySelector('h1');
                                    if (h1) h1.innerText = map['__title__'];
                                }
                                for (var key in map) {
                                    if (key === '__title__') continue;
                                    var el = document.getElementById(key);
                                    if (el) {
                                        el.innerText = map[key];
                                    }
                                }
                            } catch(e) {}
                        };
                    </script>
                </body>
                </html>
            """.trimIndent()

            val currentTab = activity.getActiveTab()
            webView.loadDataWithBaseURL(currentTab?.url, readerHtml, "text/html", "UTF-8", null)
            root.setBackgroundColor(Color.parseColor(theme.bg))
            progressBar.progressTintList = ColorStateList.valueOf(Color.parseColor(theme.accent))

            val isDarkTheme = theme.bg == "#1E1F22" || theme.bg == "#000000"
            val headerBg = if (isDarkTheme) Color.parseColor("#1E1F22") else Color.parseColor(theme.bg)
            val headerFg = if (isDarkTheme) Color.WHITE else Color.parseColor(theme.text)

            header.setBackgroundColor(headerBg)
            tvTitle.setTextColor(headerFg)
            tvTitle.text = if (currentActiveLangName != null) "Reader Mode • $currentActiveLangName" else "Reader Mode"
            btnClose.setColorFilter(headerFg)
            btnReadAloud.setColorFilter(headerFg)
            btnTranslate.setColorFilter(if (currentActiveLangCode != null) Color.parseColor(theme.accent) else headerFg)
            btnAppearance.setColorFilter(headerFg)

            val barBg = if (isDarkTheme) Color.parseColor("#27292D") else Color.parseColor(if (theme.name == "Parchment") "#EFE2C6" else "#F1F3F4")
            readAloudBar.setBackgroundColor(barBg)
            tvReadAloudStatus.setTextColor(Color.parseColor(theme.accent))
            btnPrevSentence.setColorFilter(headerFg)
            btnPlayPause.setColorFilter(headerFg)
            btnNextSentence.setColorFilter(headerFg)
            btnVoiceOptions.setColorFilter(headerFg)
            btnSpeedPill.setTextColor(headerFg)
        }

        root.addView(webView)

        fun speakSentence(index: Int) {
            if (index < 0 || index >= sentences.size || tts == null) return
            currentSentenceIndex = index
            val sentence = sentences[index]

            val activeLang = currentActiveLangCode ?: Locale.getDefault().language
            val customVoice = selectedVoice
            if (customVoice != null && customVoice.locale.language.equals(activeLang, ignoreCase = true)) {
                try {
                    tts?.voice = customVoice
                } catch (_: Exception) {}
            } else {
                if (currentActiveLangCode != null) {
                    val targetLocale = Locale.forLanguageTag(currentActiveLangCode!!)
                    val result = tts?.setLanguage(targetLocale)
                    if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                        val baseLocale = Locale.forLanguageTag(currentActiveLangCode!!.substringBefore("-"))
                        tts?.setLanguage(baseLocale)
                    }
                } else {
                    tts?.setLanguage(Locale.getDefault())
                }
            }

            tts?.setSpeechRate(ttsSpeed)
            tts?.speak(sentence, TextToSpeech.QUEUE_FLUSH, null, "haze_sent_$index")
            isTtsSpeaking = true
            activity.runOnUiThread {
                btnPlayPause.setImageResource(R.drawable.ic_pause)
            }
        }

        // --- 4. TTS (Read Aloud) Implementation with Sentence-Level Tracking ---
        tts = TextToSpeech(activity, { status ->
            if (status == TextToSpeech.SUCCESS) {
                val savedVoiceName = selectedVoiceName
                if (savedVoiceName != null) {
                    selectedVoice = tts?.voices?.find { it.name == savedVoiceName }
                }
                if (selectedVoice != null) {
                    try { tts?.voice = selectedVoice } catch (_: Exception) {}
                } else {
                    tts?.language = Locale.getDefault()
                }
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        val idx = utteranceId?.removePrefix("haze_sent_")?.toIntOrNull() ?: return
                        currentSentenceIndex = idx
                        activity.runOnUiThread {
                            webView.evaluateJavascript("window.hazeHighlightSentence($idx)", null)
                        }
                    }

                    override fun onDone(utteranceId: String?) {
                        val idx = utteranceId?.removePrefix("haze_sent_")?.toIntOrNull() ?: return
                        if (idx + 1 < sentences.size && isTtsSpeaking) {
                            speakSentence(idx + 1)
                        } else {
                            activity.runOnUiThread {
                                isTtsSpeaking = false
                                btnPlayPause.setImageResource(R.drawable.ic_play)
                            }
                        }
                    }

                    override fun onError(utteranceId: String?) {
                        activity.runOnUiThread {
                            isTtsSpeaking = false
                            btnPlayPause.setImageResource(R.drawable.ic_play)
                        }
                    }
                })
            }
        })

        btnReadAloud.setOnClickListener {
            activity.triggerStrongHaptic(it)
            if (readAloudBar.visibility == View.VISIBLE) {
                readAloudBar.visibility = View.GONE
                stopTts()
            } else {
                readAloudBar.visibility = View.VISIBLE
                speakSentence(currentSentenceIndex)
            }
        }

        btnPlayPause.setOnClickListener {
            activity.triggerStrongHaptic(it)
            if (isTtsSpeaking) {
                tts?.stop()
                isTtsSpeaking = false
                btnPlayPause.setImageResource(R.drawable.ic_play)
            } else {
                speakSentence(currentSentenceIndex)
            }
        }

        btnPrevSentence.setOnClickListener {
            activity.triggerStrongHaptic(it)
            if (currentSentenceIndex > 0) {
                speakSentence(currentSentenceIndex - 1)
            }
        }

        btnNextSentence.setOnClickListener {
            activity.triggerStrongHaptic(it)
            if (currentSentenceIndex + 1 < sentences.size) {
                speakSentence(currentSentenceIndex + 1)
            }
        }

        val speeds = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
        btnSpeedPill.setOnClickListener {
            activity.triggerStrongHaptic(it)
            val currentIdx = speeds.indexOfFirst { s -> Math.abs(s - ttsSpeed) < 0.1f }
            val nextIdx = (if (currentIdx == -1) 1 else currentIdx + 1) % speeds.size
            ttsSpeed = speeds[nextIdx]
            btnSpeedPill.text = "${ttsSpeed}x"
            tts?.setSpeechRate(ttsSpeed)
            if (isTtsSpeaking) {
                speakSentence(currentSentenceIndex)
            }
        }

        btnVoiceOptions.setOnClickListener {
            activity.triggerStrongHaptic(it)
            showVoiceSelectionDialog(currentActiveLangCode) {
                speakSentence(currentSentenceIndex)
            }
        }

        fun executeRevert() {
            stopTts()
            currentActiveLangCode = null
            currentActiveLangName = null
            currentDisplayTitle = null
            currentDisplayContentHtml = null
            sentences = originalSentences
            currentSentenceIndex = 0
            reloadContent()
            Toast.makeText(activity, "Restored original article", Toast.LENGTH_SHORT).show()
        }

        fun executeTranslation(targetCode: String, targetName: String) {
            if (targetCode == currentActiveLangCode) {
                Toast.makeText(activity, "Already translated to $targetName", Toast.LENGTH_SHORT).show()
                return
            }

            stopTts()
            isTranslating = true
            translationBar.visibility = View.VISIBLE
            tvTranslationStatus.text = "Translating article into $targetName..."

            webView.evaluateJavascript("window.hazeGetTranslatableItems()") { rawJson ->
                val cleanJson = if (rawJson != null && rawJson.startsWith("\"") && rawJson.endsWith("\"")) {
                    try {
                        org.json.JSONTokener(rawJson).nextValue().toString()
                    } catch (_: Exception) { rawJson }
                } else rawJson ?: "[]"

                val itemsToTranslate = mutableListOf<Pair<String, String>>()
                try {
                    val jsonArray = JSONArray(cleanJson)
                    for (i in 0 until jsonArray.length()) {
                        val obj = jsonArray.optJSONObject(i)
                        if (obj != null) {
                            val id = obj.optString("id")
                            val text = obj.optString("text")
                            if (id.isNotEmpty() && text.isNotEmpty()) {
                                itemsToTranslate.add(Pair(id, text))
                            }
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                if (itemsToTranslate.isEmpty()) {
                    isTranslating = false
                    translationBar.visibility = View.GONE
                    Toast.makeText(activity, "Could not extract article text to translate", Toast.LENGTH_SHORT).show()
                    return@evaluateJavascript
                }

                Thread {
                    val translatedMap = translateBatch(itemsToTranslate, targetCode)
                    activity.runOnUiThread {
                        isTranslating = false
                        translationBar.visibility = View.GONE

                        if (dialog.isShowing) {
                            currentActiveLangCode = targetCode
                            currentActiveLangName = targetName

                            // Update TTS sentences
                            val newSentences = mutableListOf<String>()
                            for (i in 0 until originalSentences.size) {
                                val tr = translatedMap["haze-s-$i"]
                                newSentences.add(if (!tr.isNullOrBlank()) tr else originalSentences[i])
                            }
                            sentences = newSentences
                            currentSentenceIndex = 0

                            // Apply to live DOM
                            val jsonMap = JSONObject()
                            for ((k, v) in translatedMap) {
                                jsonMap.put(k, v)
                            }
                            val quotedJson = JSONObject.quote(jsonMap.toString())
                            webView.evaluateJavascript("window.hazeApplyTranslations($quotedJson);", null)

                            // Update cached display title and content HTML
                            translatedMap["__title__"]?.let {
                                currentDisplayTitle = it
                            }
                            webView.evaluateJavascript("document.getElementById('haze-content').innerHTML;") { innerHtmlRaw ->
                                if (innerHtmlRaw != null && innerHtmlRaw != "null") {
                                    val cleanHtml = if (innerHtmlRaw.startsWith("\"") && innerHtmlRaw.endsWith("\"")) {
                                        try {
                                            org.json.JSONTokener(innerHtmlRaw).nextValue().toString()
                                        } catch (_: Exception) { innerHtmlRaw }
                                    } else innerHtmlRaw
                                    currentDisplayContentHtml = cleanHtml
                                }
                            }

                            val theme = themes[currentThemeIndex]
                            tvTitle.text = "Reader Mode • $targetName"
                            btnTranslate.setColorFilter(Color.parseColor(theme.accent))
                            Toast.makeText(activity, "Translated to $targetName", Toast.LENGTH_SHORT).show()
                        }
                    }
                }.start()
            }
        }

        currentTranslateAction = { code, name -> executeTranslation(code, name) }
        currentRevertAction = { executeRevert() }
        currentVoiceAction = {
            showVoiceSelectionDialog(currentActiveLangCode) {
                speakSentence(currentSentenceIndex)
            }
        }
        dialog.setOnDismissListener {
            currentTranslateAction = null
            currentRevertAction = null
            currentVoiceAction = null
        }

        // --- 5. In-Reader Translate Button ---
        btnTranslate.setOnClickListener {
            activity.triggerStrongHaptic(it)
            if (isTranslating) {
                Toast.makeText(activity, "Translation is in progress...", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val isCurrentlyTranslated = currentActiveLangCode != null
            val options = mutableListOf<String>()
            if (isCurrentlyTranslated) {
                options.add("★ Show Original Article")
            }
            for (lang in HazeTranslationManager.SUPPORTED_LANGUAGES) {
                options.add(lang.first)
            }

            AlertDialog.Builder(activity)
                .setTitle(if (isCurrentlyTranslated) "Translate / Revert" else "Translate Reader To")
                .setItems(options.toTypedArray()) { _, which ->
                    if (isCurrentlyTranslated && which == 0) {
                        executeRevert()
                        return@setItems
                    }

                    val langIndex = if (isCurrentlyTranslated) which - 1 else which
                    val chosen = HazeTranslationManager.SUPPORTED_LANGUAGES[langIndex]
                    executeTranslation(chosen.second, chosen.first)
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        // --- 6. Appearance Settings (Bottom Sheet / Popup matching Image 3) ---
        btnAppearance.setOnClickListener {
            activity.triggerStrongHaptic(it)
            showAppearanceDialog { reloadContent() }
        }

        reloadContent()
        dialog.setContentView(root)
        dialog.window?.let { w ->
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }
        dialog.show()
    }

    private fun stopTts() {
        tts?.stop()
        isTtsSpeaking = false
        activeWebView?.evaluateJavascript("document.body.classList.remove('has-active-focus'); var old = document.querySelectorAll('.haze-highlight'); for (var i = 0; i < old.length; i++) { old[i].classList.remove('haze-highlight'); }", null)
    }

    fun destroy() {
        stopTts()
        try {
            tts?.shutdown()
        } catch (_: Exception) {}
        tts = null
        readerDialog?.dismiss()
        readerDialog = null
        activeWebView?.destroy()
        activeWebView = null
    }

    private fun getVoiceDisplayName(voice: Voice, voiceNumber: Int): Pair<String, String> {
        val loc = voice.locale
        val country = loc.displayCountry.ifBlank { loc.country }
        val lang = loc.displayLanguage.ifBlank { loc.language }

        val nameLower = voice.name.lowercase(Locale.ROOT)
        val features = voice.features ?: emptySet()
        val isFemale = features.any { it.contains("female", ignoreCase = true) } ||
                nameLower.contains("female") || nameLower.contains("#female") ||
                nameLower.contains("-fem") || nameLower.contains("f1") || nameLower.contains("f2") ||
                nameLower.contains("sfg") || nameLower.contains("tpc") || nameLower.contains("w")
        val isMale = features.any { it.contains("male", ignoreCase = true) && !it.contains("female", ignoreCase = true) } ||
                nameLower.contains("male") || nameLower.contains("#male") ||
                nameLower.contains("-mal") || nameLower.contains("m1") || nameLower.contains("m2") ||
                nameLower.contains("tpd") || nameLower.contains("iom") || nameLower.contains("iol")

        val genderStr = when {
            isFemale -> "Female"
            isMale -> "Male"
            voiceNumber % 2 == 0 -> "Female"
            else -> "Male"
        }

        val flag = when (loc.country.uppercase(Locale.ROOT)) {
            "US" -> "🇺🇸"
            "GB", "UK" -> "🇬🇧"
            "IN" -> "🇮🇳"
            "AU" -> "🇦🇺"
            "CA" -> "🇨🇦"
            "IE" -> "🇮🇪"
            "NZ" -> "🇳🇿"
            "ZA" -> "🇿🇦"
            "ES" -> "🇪🇸"
            "MX" -> "🇲🇽"
            "FR" -> "🇫🇷"
            "DE" -> "🇩🇪"
            "IT" -> "🇮🇹"
            "JP" -> "🇯🇵"
            "CN" -> "🇨🇳"
            "RU" -> "🇷🇺"
            "BR" -> "🇧🇷"
            else -> "🗣️"
        }

        val regionText = if (country.isNotBlank()) "$lang ($country)" else lang
        val title = "$flag  $regionText • Voice $voiceNumber ($genderStr)"

        val variantRaw = voice.name.substringAfter("-x-").substringBefore("#").substringBefore("-")
        val cleanVariant = if (variantRaw.isNotBlank() && variantRaw != voice.name) variantRaw else voice.name.substringAfterLast("-", "Default")
        val isLocal = !voice.isNetworkConnectionRequired
        val subtitle = if (isLocal) "On-device (High Quality) • $cleanVariant" else "Natural Studio • $cleanVariant"

        return Pair(title, subtitle)
    }

    private fun showVoiceSelectionDialog(activeLangCode: String?, onVoiceSelected: () -> Unit) {
        val activeLang = activeLangCode ?: Locale.getDefault().language
        val currentVoiceName = selectedVoice?.name ?: tts?.voice?.name

        var rawVoices = tts?.voices
            ?.filter { it.locale.language.equals(activeLang, ignoreCase = true) }
            ?: emptyList()

        if (rawVoices.isEmpty()) {
            rawVoices = tts?.voices?.toList() ?: emptyList()
        }

        if (rawVoices.isEmpty()) {
            Toast.makeText(activity, "Default voice active. Extra voices can be installed in Android System Settings -> Text-to-Speech.", Toast.LENGTH_LONG).show()
            return
        }

        val availableVoices = rawVoices.sortedWith(
            compareBy<Voice> { it.name != currentVoiceName }
                .thenBy { it.locale.country }
                .thenBy { it.name }
        )

        val isDark = prefs.getBoolean("is_dark", false)
        val dialogBg = if (isDark) Color.parseColor("#1E1F22") else Color.WHITE
        val itemBg = if (isDark) Color.parseColor("#27292D") else Color.parseColor("#F8F9FA")
        val itemSelectedBg = if (isDark) Color.parseColor("#1E3250") else Color.parseColor("#E8F0FE")
        val textColor = if (isDark) Color.WHITE else Color.parseColor("#1C1B1F")
        val subTextColor = if (isDark) Color.parseColor("#9AA0A6") else Color.parseColor("#5F6368")
        val accentColor = Color.parseColor(prefs.getString("accent_color", "#0A56D0") ?: "#0A56D0")
        val borderColor = if (isDark) Color.parseColor("#38393A") else Color.parseColor("#E0E0E0")

        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val rootCard = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dpToPx(20)
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                cornerRadius = dpToPx(28).toFloat()
                setColor(dialogBg)
                setStroke(dpToPx(1), borderColor)
            }
        }

        // Header Row
        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dpToPx(16))
        }

        val ivIcon = ImageView(activity).apply {
            setImageResource(R.drawable.ic_volume_up)
            setColorFilter(accentColor)
            val iconPad = dpToPx(8)
            setPadding(iconPad, iconPad, iconPad, iconPad)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (isDark) Color.parseColor("#26FFFFFF") else Color.parseColor("#140A56D0"))
            }
            layoutParams = LinearLayout.LayoutParams(dpToPx(40), dpToPx(40)).apply {
                marginEnd = dpToPx(12)
            }
        }
        header.addView(ivIcon)

        val titleCol = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val tvHeader = TextView(activity).apply {
            text = "Voice & Accent"
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setTextColor(textColor)
        }
        val tvSub = TextView(activity).apply {
            text = "${availableVoices.size} voices available • Tap to switch"
            textSize = 12f
            setTextColor(subTextColor)
        }
        titleCol.addView(tvHeader)
        titleCol.addView(tvSub)
        header.addView(titleCol)

        val btnClose = ImageView(activity).apply {
            setImageResource(R.drawable.ic_close)
            setColorFilter(subTextColor)
            val btnPad = dpToPx(8)
            setPadding(btnPad, btnPad, btnPad, btnPad)
            setBackgroundResource(getSelectableRes())
            layoutParams = LinearLayout.LayoutParams(dpToPx(36), dpToPx(36))
            setOnClickListener { dialog.dismiss() }
        }
        header.addView(btnClose)
        rootCard.addView(header)

        // Divider
        val divider = View(activity).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(1)).apply {
                bottomMargin = dpToPx(12)
            }
            setBackgroundColor(borderColor)
        }
        rootCard.addView(divider)

        // Scrollable list of voice items
        val scrollView = ScrollView(activity).apply {
            val maxH = (activity.resources.displayMetrics.heightPixels * 0.52).toInt()
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, maxH)
            isVerticalScrollBarEnabled = true
        }

        val listContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }

        val localeCounter = mutableMapOf<String, Int>()

        availableVoices.forEach { voice ->
            val countryCode = voice.locale.country.ifBlank { "INTL" }
            val counter = localeCounter.getOrDefault(countryCode, 0) + 1
            localeCounter[countryCode] = counter

            val (title, subtitle) = getVoiceDisplayName(voice, counter)
            val isSelected = voice.name == currentVoiceName

            val itemCard = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val pX = dpToPx(14)
                val pY = dpToPx(12)
                setPadding(pX, pY, pX, pY)
                val corner = dpToPx(16).toFloat()
                background = GradientDrawable().apply {
                    cornerRadius = corner
                    setColor(if (isSelected) itemSelectedBg else itemBg)
                    setStroke(dpToPx(1), if (isSelected) accentColor else Color.TRANSPARENT)
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = dpToPx(8)
                }
                isClickable = true
                isFocusable = true
            }

            val textCol = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val tvItemTitle = TextView(activity).apply {
                text = if (isSelected) "$title  ✓" else title
                textSize = 14f
                setTypeface(null, if (isSelected) Typeface.BOLD else Typeface.NORMAL)
                setTextColor(if (isSelected) accentColor else textColor)
            }
            val tvItemSub = TextView(activity).apply {
                text = if (isSelected) "Active Voice • $subtitle" else subtitle
                textSize = 11.5f
                setTextColor(if (isSelected) accentColor else subTextColor)
            }
            textCol.addView(tvItemTitle)
            textCol.addView(tvItemSub)
            itemCard.addView(textCol)

            // Test / Preview Button
            val btnPreview = ImageView(activity).apply {
                setImageResource(R.drawable.ic_volume_up)
                setColorFilter(accentColor)
                val padS = dpToPx(6)
                setPadding(padS, padS, padS, padS)
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(if (isDark) Color.parseColor("#22FFFFFF") else Color.parseColor("#140A56D0"))
                }
                layoutParams = LinearLayout.LayoutParams(dpToPx(32), dpToPx(32)).apply {
                    marginStart = dpToPx(8)
                }
                contentDescription = "Test Voice"
                setOnClickListener {
                    activity.triggerStrongHaptic(it)
                    try {
                        tts?.voice = voice
                        tts?.setSpeechRate(ttsSpeed)
                        tts?.speak("This is a preview of this voice.", TextToSpeech.QUEUE_FLUSH, null, "voice_preview")
                    } catch (_: Exception) {}
                }
            }
            itemCard.addView(btnPreview)

            itemCard.setOnClickListener {
                activity.triggerStrongHaptic(it)
                selectedVoice = voice
                selectedVoiceName = voice.name
                prefs.edit().putString("reader_voice_name", voice.name).apply()
                try {
                    tts?.voice = voice
                } catch (_: Exception) {}

                Toast.makeText(activity, "Voice set to $title", Toast.LENGTH_SHORT).show()
                if (isTtsSpeaking) {
                    onVoiceSelected()
                } else {
                    try {
                        tts?.setSpeechRate(ttsSpeed)
                        tts?.speak("Voice selected.", TextToSpeech.QUEUE_FLUSH, null, "voice_preview")
                    } catch (_: Exception) {}
                }
                dialog.dismiss()
            }

            listContainer.addView(itemCard)
        }

        scrollView.addView(listContainer)
        rootCard.addView(scrollView)

        dialog.setContentView(rootCard)
        dialog.window?.let { w ->
            w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            w.setLayout(
                (activity.resources.displayMetrics.widthPixels * 0.92).toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        dialog.show()
    }

    /**
     * Renders the Microsoft Edge-inspired Appearance Settings modal (Image 3)
     */
    private fun showAppearanceDialog(onChanged: () -> Unit) {
        val isDark = prefs.getBoolean("is_dark", false)
        val surfaceColor = if (isDark) Color.parseColor("#25272B") else Color.WHITE
        val textColor = if (isDark) Color.parseColor("#F1F3E8") else Color.parseColor("#1C1B1F")
        val accentColor = Color.parseColor(prefs.getString("accent_color", "#0A56D0") ?: "#0A56D0")

        val popView = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(22), dpToPx(20), dpToPx(22), dpToPx(20))
            background = GradientDrawable().apply {
                cornerRadius = dpToPx(24).toFloat()
                setColor(surfaceColor)
            }
        }

        // 1. Font Dropdown
        val tvFontLbl = TextView(activity).apply {
            text = "Font"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(textColor)
        }
        val fonts = arrayOf("Outfit (Modern Sans)", "Sitka (Editorial Serif)", "Georgia (Book Serif)", "Merriweather (Literary)", "OpenDyslexic (Accessible)", "Monospace")
        val fontSpinner = Spinner(activity).apply {
            adapter = object : ArrayAdapter<String>(activity, android.R.layout.simple_spinner_item, fonts) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                    return super.getView(position, convertView, parent).apply {
                        (this as? TextView)?.apply {
                            setTextColor(textColor)
                            textSize = 15f
                        }
                    }
                }

                override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                    return super.getDropDownView(position, convertView, parent).apply {
                        (this as? TextView)?.apply {
                            setTextColor(textColor)
                            setPadding(dpToPx(16), dpToPx(12), dpToPx(16), dpToPx(12))
                        }
                    }
                }
            }.also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }

            val initialFontIdx = when (currentFontName) {
                "Sitka" -> 1
                "Georgia" -> 2
                "Merriweather" -> 3
                "OpenDyslexic" -> 4
                "Monospace" -> 5
                else -> 0
            }
            setSelection(initialFontIdx)

            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                    val chosen = when (position) {
                        1 -> "Sitka"
                        2 -> "Georgia"
                        3 -> "Merriweather"
                        4 -> "OpenDyslexic"
                        5 -> "Monospace"
                        else -> "Outfit"
                    }
                    if (currentFontName != chosen) {
                        currentFontName = chosen
                        onChanged()
                    }
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }
        popView.addView(tvFontLbl)
        popView.addView(fontSpinner)

        // 2. Font Size (Slider with Small / Large labels)
        val tvSizeLbl = TextView(activity).apply {
            text = "Font size"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(textColor)
            setPadding(0, dpToPx(16), 0, 0)
        }
        val sizeSeek = SeekBar(activity).apply {
            max = 14
            progress = (currentFontSizePx - 14).coerceIn(0, 14)
            progressTintList = ColorStateList.valueOf(accentColor)
            thumbTintList = ColorStateList.valueOf(accentColor)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    currentFontSizePx = 14 + progress
                    onChanged()
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        val sizeLabels = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            val tvSmall = TextView(activity).apply { text = "Small"; textSize = 11f; setTextColor(Color.GRAY) }
            val tvLarge = TextView(activity).apply { text = "Large"; textSize = 11f; setTextColor(Color.GRAY); gravity = Gravity.END; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
            addView(tvSmall)
            addView(tvLarge)
        }
        popView.addView(tvSizeLbl)
        popView.addView(sizeSeek)
        popView.addView(sizeLabels)

        // 3. Column Width (Slider with Narrow / Wide labels)
        val tvWidthLbl = TextView(activity).apply {
            text = "Column width"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(textColor)
            setPadding(0, dpToPx(16), 0, 0)
        }
        val widthSeek = SeekBar(activity).apply {
            max = 4
            progress = currentColumnWidthProgress
            progressTintList = ColorStateList.valueOf(accentColor)
            thumbTintList = ColorStateList.valueOf(accentColor)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    currentColumnWidthProgress = progress
                    onChanged()
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        val widthLabels = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            val tvNarrow = TextView(activity).apply { text = "Narrow"; textSize = 11f; setTextColor(Color.GRAY) }
            val tvWide = TextView(activity).apply { text = "Wide"; textSize = 11f; setTextColor(Color.GRAY); gravity = Gravity.END; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
            addView(tvNarrow)
            addView(tvWide)
        }
        popView.addView(tvWidthLbl)
        popView.addView(widthSeek)
        popView.addView(widthLabels)

        // 4. Themes Matrix (11 circular swatches with active checkmark)
        val tvThemeLbl = TextView(activity).apply {
            text = "Themes"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(textColor)
            setPadding(0, dpToPx(18), 0, dpToPx(10))
        }
        popView.addView(tvThemeLbl)

        // Row 1: 6 themes
        val themeGrid = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val row1 = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, 0, 0, dpToPx(8)) }
        val row2 = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }

        var appearanceDialog: AlertDialog? = null

        fun renderThemeButtons() {
            row1.removeAllViews()
            row2.removeAllViews()

            for (i in themes.indices) {
                val theme = themes[i]
                val isSelected = i == currentThemeIndex
                val circle = FrameLayout(activity).apply {
                    layoutParams = LinearLayout.LayoutParams(dpToPx(40), dpToPx(40)).apply {
                        marginEnd = dpToPx(8)
                    }
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(Color.parseColor(theme.bg))
                        setStroke(
                            if (isSelected) dpToPx(3) else dpToPx(1),
                            if (isSelected) Color.parseColor(theme.accent) else Color.parseColor("#808080")
                        )
                    }

                    if (isSelected) {
                        val check = TextView(activity).apply {
                            text = "✓"
                            textSize = 16f
                            setTypeface(null, Typeface.BOLD)
                            setTextColor(Color.parseColor(theme.text))
                            gravity = Gravity.CENTER
                            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
                        }
                        addView(check)
                    }

                    setOnClickListener {
                        activity.triggerStrongHaptic(it)
                        currentThemeIndex = i
                        renderThemeButtons()
                        onChanged()
                    }
                }

                if (i < 6) row1.addView(circle) else row2.addView(circle)
            }
        }
        renderThemeButtons()
        themeGrid.addView(row1)
        themeGrid.addView(row2)
        popView.addView(themeGrid)

        // 5. Line Focus Toggle (Haze Indigenous Innovation)
        val lineFocusRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dpToPx(18), 0, 0)
        }
        val tvLineFocus = TextView(activity).apply {
            text = "Line Focus (Reading Shield)"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(textColor)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val switchLineFocus = Switch(activity).apply {
            isChecked = isLineFocusEnabled
            thumbTintList = ColorStateList.valueOf(accentColor)
            trackTintList = ColorStateList.valueOf(Color.GRAY)
            setOnCheckedChangeListener { _, isChecked ->
                isLineFocusEnabled = isChecked
                onChanged()
            }
        }
        lineFocusRow.addView(tvLineFocus)
        lineFocusRow.addView(switchLineFocus)
        popView.addView(lineFocusRow)

        appearanceDialog = AlertDialog.Builder(activity)
            .setTitle("Appearance settings")
            .setView(popView)
            .setPositiveButton("Done", null)
            .create()

        appearanceDialog.show()
        appearanceDialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        appearanceDialog.findViewById<TextView>(
            activity.resources.getIdentifier("alertTitle", "id", "android")
        )?.setTextColor(textColor)
    }

    /**
     * Translates a list of (id, text) pairs in batches using Google's translate service.
     * Preserves index markers [[[0]]], [[[1]]], etc. to map translations back to DOM element IDs.
     */
    private fun translateBatch(
        items: List<Pair<String, String>>,
        targetLang: String
    ): Map<String, String> {
        val results = mutableMapOf<String, String>()
        if (items.isEmpty()) return results

        // Group into chunks of ~30 items or ~2,800 chars to respect request sizes
        val chunks = mutableListOf<List<Pair<String, String>>>()
        var currentChunk = mutableListOf<Pair<String, String>>()
        var currentChunkLen = 0

        for (item in items) {
            val itemLen = item.second.length + 15
            if (currentChunk.isNotEmpty() && (currentChunkLen + itemLen > 2800 || currentChunk.size >= 35)) {
                chunks.add(currentChunk)
                currentChunk = mutableListOf()
                currentChunkLen = 0
            }
            currentChunk.add(item)
            currentChunkLen += itemLen
        }
        if (currentChunk.isNotEmpty()) {
            chunks.add(currentChunk)
        }

        val pattern = Pattern.compile("\\[\\s*\\[\\s*\\[\\s*(\\d+)\\s*\\]\\s*\\]\\s*\\]\\s*(.*?)(?=\\[\\s*\\[\\s*\\[\\s*\\d+\\s*\\]\\s*\\]\\s*\\]|$)", Pattern.DOTALL)

        for (chunk in chunks) {
            try {
                val sb = StringBuilder()
                for (i in chunk.indices) {
                    sb.append("[[[").append(i).append("]]] ").append(chunk[i].second.trim()).append("\n")
                }

                val postPayload = "q=" + URLEncoder.encode(sb.toString(), "UTF-8")
                val url = URL("https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=$targetLang&dt=t")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 12000
                conn.readTimeout = 12000
                conn.doOutput = true
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 11; Pixel 5) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")

                conn.outputStream.use { os ->
                    os.write(postPayload.toByteArray(Charsets.UTF_8))
                    os.flush()
                }

                if (conn.responseCode == 200) {
                    val resp = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    val fullText = parseTranslationResponse(resp)
                    val matcher = pattern.matcher(fullText)
                    while (matcher.find()) {
                        val idx = matcher.group(1)?.toIntOrNull() ?: continue
                        val text = matcher.group(2)?.trim() ?: ""
                        if (idx in chunk.indices && text.isNotEmpty()) {
                            results[chunk[idx].first] = text
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // Fill in any missed items with original text
        for (item in items) {
            if (!results.containsKey(item.first)) {
                results[item.first] = item.second
            }
        }

        return results
    }

    private fun parseTranslationResponse(responseBody: String): String {
        return try {
            val sb = StringBuilder()
            val outer = JSONArray(responseBody)
            val segments = outer.getJSONArray(0)
            for (i in 0 until segments.length()) {
                val seg = segments.optJSONArray(i)
                if (seg != null && seg.length() > 0) {
                    sb.append(seg.optString(0, ""))
                }
            }
            sb.toString()
        } catch (_: Exception) {
            responseBody
        }
    }
}
