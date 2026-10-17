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
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.*
import org.json.JSONObject
import java.util.Locale

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
                    <h1>$title</h1>
                    <div class="article-meta">$authorHtml<span>$readTime</span>$wordCountHtml</div>
                    <hr class="divider"/>
                    <div id="haze-content">$contentHtml</div>
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
            btnClose.setColorFilter(headerFg)
            btnReadAloud.setColorFilter(headerFg)
            btnTranslate.setColorFilter(headerFg)
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
                tts?.language = Locale.getDefault()
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
            showVoiceSelectionDialog()
        }

        // --- 5. Translate Button ---
        btnTranslate.setOnClickListener {
            activity.triggerStrongHaptic(it)
            val names = HazeTranslationManager.SUPPORTED_LANGUAGES.map { it.first }.toTypedArray()
            AlertDialog.Builder(activity)
                .setTitle("Translate Article To")
                .setItems(names) { _, which ->
                    val chosen = HazeTranslationManager.SUPPORTED_LANGUAGES[which]
                    val currentTab = activity.getActiveTab()
                    if (currentTab != null) {
                        dialog.dismiss()
                        HazeTranslationManager.translateTab(activity, currentTab, chosen.second, chosen.third)
                    }
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

    private fun showVoiceSelectionDialog() {
        val availableVoices = tts?.voices
            ?.filter { it.locale.language == Locale.getDefault().language && !it.isNetworkConnectionRequired }
            ?: emptyList()

        if (availableVoices.isEmpty()) {
            Toast.makeText(activity, "Default system voice active. Install extra TTS voices in System Settings for more accents.", Toast.LENGTH_LONG).show()
            return
        }

        val voiceNames = availableVoices.map { voice ->
            val gender = if (voice.name.contains("female", ignoreCase = true) || voice.name.contains("f1", ignoreCase = true)) "Female" else "Male"
            "$gender - ${voice.locale.displayName} (${voice.name.substringAfterLast("-", "Default")})"
        }.toTypedArray()

        AlertDialog.Builder(activity)
            .setTitle("Select Voice Accent")
            .setItems(voiceNames) { _, which ->
                tts?.voice = availableVoices[which]
                Toast.makeText(activity, "Voice set to ${availableVoices[which].name}", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
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
}
