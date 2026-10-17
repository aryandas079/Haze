package com.example.haze

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.speech.tts.TextToSpeech
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.webkit.WebView
import android.widget.*
import com.google.android.material.bottomsheet.BottomSheetDialog
import java.net.URLEncoder
import java.util.*

object HazeContextMenuManager {

    private var tts: TextToSpeech? = null

    private fun initTts(context: Context, onReady: (TextToSpeech) -> Unit) {
        if (tts != null) {
            onReady(tts!!)
            return
        }
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.getDefault()
                tts?.let { onReady(it) }
            }
        }
    }

    fun speakText(context: Context, text: String) {
        if (text.isEmpty()) return
        initTts(context) { engine ->
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "HazeTTS")
            Toast.makeText(context, "Reading aloud...", Toast.LENGTH_SHORT).show()
        }
    }

    fun stopSpeaking() {
        tts?.stop()
    }

    // ==========================================
    // LINK CONTEXT MENU
    // ==========================================
    fun showLinkContextMenu(activity: MainActivity, url: String) {
        activity.triggerStrongHaptic(isHeavy = true)
        val dialog = BottomSheetDialog(activity)
        val isDark = activity.getSharedPreferences("HazePrefs", Context.MODE_PRIVATE).getBoolean("is_dark", false)
        val accentColor = Color.parseColor(activity.getSharedPreferences("HazePrefs", Context.MODE_PRIVATE).getString("accent_color", "#0A56D0"))
        val bgColor = if (isDark) Color.parseColor("#1E1E1E") else Color.parseColor("#FFFFFF")
        val textColor = if (isDark) Color.WHITE else Color.parseColor("#202124")
        val subTextColor = if (isDark) Color.parseColor("#9AA0A6") else Color.parseColor("#5F6368")

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgColor)
            setPadding(dpToPx(activity, 16), dpToPx(activity, 16), dpToPx(activity, 16), dpToPx(activity, 24))
        }

        // Header with link URL
        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpToPx(activity, 8), 0, dpToPx(activity, 8), dpToPx(activity, 12))
        }
        val linkIcon = ImageView(activity).apply {
            setImageResource(R.drawable.ic_link)
            setColorFilter(accentColor)
            layoutParams = LinearLayout.LayoutParams(dpToPx(activity, 22), dpToPx(activity, 22)).apply {
                marginEnd = dpToPx(activity, 12)
            }
        }
        val urlTv = TextView(activity).apply {
            text = url
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(subTextColor)
            isSingleLine = true
        }
        header.addView(linkIcon)
        header.addView(urlTv)
        root.addView(header)

        fun addItem(title: String, iconRes: Int, onClick: () -> Unit) {
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(activity, 48))
                setPadding(dpToPx(activity, 12), 0, dpToPx(activity, 12), 0)
                setBackgroundResource(getSelectableRes(activity))
                setOnClickListener {
                    activity.triggerStrongHaptic(it)
                    dialog.dismiss()
                    onClick()
                }
            }
            val icon = ImageView(activity).apply {
                setImageResource(iconRes)
                setColorFilter(subTextColor)
                layoutParams = LinearLayout.LayoutParams(dpToPx(activity, 22), dpToPx(activity, 22)).apply {
                    marginEnd = dpToPx(activity, 16)
                }
            }
            val text = TextView(activity).apply {
                this.text = title
                textSize = 15f
                setTextColor(textColor)
            }
            row.addView(icon)
            row.addView(text)
            root.addView(row)
        }

        addItem("Open link in new tab", R.drawable.ic_add) {
            activity.addNewTab(url)
            Toast.makeText(activity, "Opened in new tab", Toast.LENGTH_SHORT).show()
        }

        addItem("Open link in private tab", R.drawable.ic_private) {
            activity.openPrivateTab(url)
            Toast.makeText(activity, "Opened in private tab", Toast.LENGTH_SHORT).show()
        }

        addItem("Open link in background tab", R.drawable.ic_globe) {
            activity.addNewBackgroundTab(url)
            Toast.makeText(activity, "Opened in background", Toast.LENGTH_SHORT).show()
        }

        addItem("Copy link address", R.drawable.ic_link) {
            val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("URL", url))
            Toast.makeText(activity, "Link copied to clipboard", Toast.LENGTH_SHORT).show()
        }

        addItem("Share link", R.drawable.ic_share) {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, url)
            }
            activity.startActivity(Intent.createChooser(intent, "Share link"))
        }

        if (HazeFileNameResolver.isYouTubeUrl(url)) {
            addItem("Download video / audio", R.drawable.ic_download) {
                HazeDownloadsManager.handleYouTubeLinkDownload(activity, url)
            }
        } else {
            addItem("Download link", R.drawable.ic_download) {
                val ua = (activity.getActiveTab()?.browserTab?.renderView as? WebView)?.settings?.userAgentString ?: ""
                val resolvedName = HazeFileNameResolver.resolveBestFileName(url)
                HazeDownloadsManager.initDownload(activity, url, ua, "", "*/*", -1, false, resolvedName)
            }
        }

        addItem("Open in external app", R.drawable.ic_open_in_new) {
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                activity.startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(activity, "No external app available", Toast.LENGTH_SHORT).show()
            }
        }

        dialog.setContentView(root)
        dialog.show()
    }

    // ==========================================
    // IMAGE CONTEXT MENU
    // ==========================================
    fun showImageContextMenu(activity: MainActivity, imageUrl: String) {
        activity.triggerStrongHaptic(isHeavy = true)
        val dialog = BottomSheetDialog(activity)
        val isDark = activity.getSharedPreferences("HazePrefs", Context.MODE_PRIVATE).getBoolean("is_dark", false)
        val accentColor = Color.parseColor(activity.getSharedPreferences("HazePrefs", Context.MODE_PRIVATE).getString("accent_color", "#0A56D0"))
        val bgColor = if (isDark) Color.parseColor("#1E1E1E") else Color.parseColor("#FFFFFF")
        val textColor = if (isDark) Color.WHITE else Color.parseColor("#202124")
        val subTextColor = if (isDark) Color.parseColor("#9AA0A6") else Color.parseColor("#5F6368")

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgColor)
            setPadding(dpToPx(activity, 16), dpToPx(activity, 16), dpToPx(activity, 16), dpToPx(activity, 24))
        }

        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpToPx(activity, 8), 0, dpToPx(activity, 8), dpToPx(activity, 12))
        }
        val imgIcon = ImageView(activity).apply {
            setImageResource(R.drawable.ic_image)
            setColorFilter(accentColor)
            layoutParams = LinearLayout.LayoutParams(dpToPx(activity, 22), dpToPx(activity, 22)).apply {
                marginEnd = dpToPx(activity, 12)
            }
        }
        val urlTv = TextView(activity).apply {
            text = imageUrl
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(subTextColor)
            isSingleLine = true
        }
        header.addView(imgIcon)
        header.addView(urlTv)
        root.addView(header)

        fun addItem(title: String, iconRes: Int, onClick: () -> Unit) {
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(activity, 48))
                setPadding(dpToPx(activity, 12), 0, dpToPx(activity, 12), 0)
                setBackgroundResource(getSelectableRes(activity))
                setOnClickListener {
                    activity.triggerStrongHaptic(it)
                    dialog.dismiss()
                    onClick()
                }
            }
            val icon = ImageView(activity).apply {
                setImageResource(iconRes)
                setColorFilter(subTextColor)
                layoutParams = LinearLayout.LayoutParams(dpToPx(activity, 22), dpToPx(activity, 22)).apply {
                    marginEnd = dpToPx(activity, 16)
                }
            }
            val text = TextView(activity).apply {
                this.text = title
                textSize = 15f
                setTextColor(textColor)
            }
            row.addView(icon)
            row.addView(text)
            root.addView(row)
        }

        addItem("Open image in new tab", R.drawable.ic_add) {
            activity.addNewTab(imageUrl)
        }

        addItem("Download image", R.drawable.ic_download) {
            if (imageUrl.startsWith("data:")) {
                HazeDownloadsManager.downloadDataUri(activity, imageUrl)
            } else if (imageUrl.startsWith("blob:")) {
                HazeDownloadsManager.downloadBlobUrl(activity, imageUrl, "image/*")
            } else {
                val ua = (activity.getActiveTab()?.browserTab?.renderView as? WebView)?.settings?.userAgentString ?: ""
                val resolvedName = HazeFileNameResolver.resolveBestFileName(imageUrl, null, "image/jpeg", null, "jpg")
                HazeDownloadsManager.initDownload(activity, imageUrl, ua, "", "image/jpeg", -1, false, resolvedName)
            }
        }

        addItem("Copy image address", R.drawable.ic_link) {
            val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("Image URL", imageUrl))
            Toast.makeText(activity, "Image link copied", Toast.LENGTH_SHORT).show()
        }

        addItem("Share image", R.drawable.ic_share) {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, imageUrl)
            }
            activity.startActivity(Intent.createChooser(intent, "Share image link"))
        }

        addItem("Copy text from image", R.drawable.ic_code) {
            HazeWebContentToolsManager.copyTextFromImageUrl(activity, imageUrl)
        }

        addItem("Visual search with Google", R.drawable.ic_lens) {
            val searchUrl = "https://lens.google.com/uploadbyurl?url=${URLEncoder.encode(imageUrl, "UTF-8")}"
            activity.addNewTab(searchUrl)
        }

        dialog.setContentView(root)
        dialog.show()
    }

    // ==========================================
    // WEBPAGE EMPTY AREA CONTEXT MENU
    // ==========================================
    fun showPageContextMenu(activity: MainActivity, currentUrl: String, currentTitle: String, tab: com.example.haze.engine.IBrowserTab) {
        activity.triggerStrongHaptic(isHeavy = true)
        val dialog = BottomSheetDialog(activity)
        val isDark = activity.getSharedPreferences("HazePrefs", Context.MODE_PRIVATE).getBoolean("is_dark", false)
        val accentColor = Color.parseColor(activity.getSharedPreferences("HazePrefs", Context.MODE_PRIVATE).getString("accent_color", "#0A56D0"))
        val bgColor = if (isDark) Color.parseColor("#1E1E1E") else Color.parseColor("#FFFFFF")
        val textColor = if (isDark) Color.WHITE else Color.parseColor("#202124")
        val subTextColor = if (isDark) Color.parseColor("#9AA0A6") else Color.parseColor("#5F6368")

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgColor)
            setPadding(dpToPx(activity, 16), dpToPx(activity, 16), dpToPx(activity, 16), dpToPx(activity, 24))
        }

        // Fast Action Bar: Back, Forward, Reload, Star
        val quickActions = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(activity, 48)).apply {
                bottomMargin = dpToPx(activity, 12)
            }
        }

        fun addQuickButton(iconRes: Int, tooltip: String, onClick: () -> Unit) {
            val btn = ImageButton(activity).apply {
                setImageResource(iconRes)
                setColorFilter(textColor)
                setBackgroundResource(getSelectableRes(activity))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
                setOnClickListener {
                    activity.triggerStrongHaptic(it)
                    dialog.dismiss()
                    onClick()
                }
            }
            quickActions.addView(btn)
        }

        addQuickButton(R.drawable.ic_chevron_up, "Back") { if (tab.navigationController.canGoBack()) tab.navigationController.goBack() }
        addQuickButton(R.drawable.ic_chevron_down, "Forward") { if (tab.navigationController.canGoForward()) tab.navigationController.goForward() }
        addQuickButton(R.drawable.ic_close, "Reload") { tab.reload() }
        val isStarred = HazeDatabase.getInstance(activity).isBookmarked(currentUrl)
        addQuickButton(if (isStarred) R.drawable.ic_star_filled else R.drawable.ic_star, "Bookmark") {
            HazeBookmarksManager.showBookmarkThisPageDialog(activity, currentTitle, currentUrl)
        }

        root.addView(quickActions)

        fun addItem(title: String, iconRes: Int, onClick: () -> Unit) {
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(activity, 46))
                setPadding(dpToPx(activity, 12), 0, dpToPx(activity, 12), 0)
                setBackgroundResource(getSelectableRes(activity))
                setOnClickListener {
                    activity.triggerStrongHaptic(it)
                    dialog.dismiss()
                    onClick()
                }
            }
            val icon = ImageView(activity).apply {
                setImageResource(iconRes)
                setColorFilter(subTextColor)
                layoutParams = LinearLayout.LayoutParams(dpToPx(activity, 22), dpToPx(activity, 22)).apply {
                    marginEnd = dpToPx(activity, 16)
                }
            }
            val text = TextView(activity).apply {
                this.text = title
                textSize = 15f
                setTextColor(textColor)
            }
            row.addView(icon)
            row.addView(text)
            root.addView(row)
        }

        addItem("Create QR code for this page", R.drawable.ic_qr_code) {
            showQrCodeDialog(activity, currentUrl)
        }

        addItem("Read aloud", R.drawable.ic_volume_up) {
            tab.evaluateJavascript("(function() { return document.body.innerText.substring(0, 3000); })();") { text ->
                val clean = text?.trim('"', '\'') ?: ""
                speakText(activity, clean)
            }
        }

        addItem("Translate page", R.drawable.ic_translate) {
            val currentTab = activity.getActiveTab()
            HazeTranslationManager.translateTab(activity, currentTab, "en", "English")
        }

        addItem("Visual search with Google Lens", R.drawable.ic_lens) {
            val lensUrl = "https://lens.google.com/uploadbyurl?url=${URLEncoder.encode(currentUrl, "UTF-8")}"
            activity.addNewTab(lensUrl)
        }

        addItem("Print / Save as PDF", R.drawable.ic_download) {
            activity.printCurrentPage()
        }

        addItem("Screenshot page", R.drawable.ic_fullscreen) {
            activity.captureCurrentTabScreenshot()
        }

        addItem("View page source", R.drawable.ic_code) {
            activity.addNewTab("view-source:$currentUrl")
        }

        addItem("Inspect Element", R.drawable.ic_code) {
            HazeDevToolsManager.showDevToolsDialog(activity, tab, initialTab = "ELEMENTS")
        }

        dialog.setContentView(root)
        dialog.show()
    }

    // ==========================================
    // TEXT SELECTION CONTEXT MENU
    // ==========================================
    fun showTextSelectionContextMenu(activity: MainActivity, selectedText: String) {
        if (selectedText.trim().isEmpty()) return
        activity.triggerStrongHaptic()

        val dialog = BottomSheetDialog(activity)
        val isDark = activity.getSharedPreferences("HazePrefs", Context.MODE_PRIVATE).getBoolean("is_dark", false)
        val accentColor = Color.parseColor(activity.getSharedPreferences("HazePrefs", Context.MODE_PRIVATE).getString("accent_color", "#0A56D0"))
        val bgColor = if (isDark) Color.parseColor("#1E1E1E") else Color.parseColor("#FFFFFF")
        val textColor = if (isDark) Color.WHITE else Color.parseColor("#202124")
        val subTextColor = if (isDark) Color.parseColor("#9AA0A6") else Color.parseColor("#5F6368")

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgColor)
            setPadding(dpToPx(activity, 16), dpToPx(activity, 16), dpToPx(activity, 16), dpToPx(activity, 24))
        }

        // Preview snippet
        val previewSnippet = TextView(activity).apply {
            text = "“${if (selectedText.length > 80) selectedText.substring(0, 77) + "..." else selectedText}”"
            textSize = 13f
            setTypeface(null, Typeface.ITALIC)
            setTextColor(subTextColor)
            setPadding(dpToPx(activity, 10), 0, dpToPx(activity, 10), dpToPx(activity, 12))
            isSingleLine = true
        }
        root.addView(previewSnippet)

        fun addItem(title: String, iconRes: Int, isHighlight: Boolean = false, onClick: () -> Unit) {
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(activity, 46))
                setPadding(dpToPx(activity, 12), 0, dpToPx(activity, 12), 0)
                setBackgroundResource(getSelectableRes(activity))
                setOnClickListener {
                    activity.triggerStrongHaptic(it)
                    dialog.dismiss()
                    onClick()
                }
            }
            val icon = ImageView(activity).apply {
                setImageResource(iconRes)
                setColorFilter(if (isHighlight) accentColor else subTextColor)
                layoutParams = LinearLayout.LayoutParams(dpToPx(activity, 22), dpToPx(activity, 22)).apply {
                    marginEnd = dpToPx(activity, 16)
                }
            }
            val text = TextView(activity).apply {
                this.text = title
                textSize = 15f
                setTextColor(if (isHighlight) accentColor else textColor)
                if (isHighlight) setTypeface(null, Typeface.BOLD)
            }
            row.addView(icon)
            row.addView(text)
            root.addView(row)
        }

        addItem("Copy", R.drawable.ic_link) {
            val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("Selected Text", selectedText))
            Toast.makeText(activity, "Copied to clipboard", Toast.LENGTH_SHORT).show()
        }

        addItem("Search selected text", R.drawable.ic_search) {
            activity.loadUrlFromInput(selectedText)
        }

        addItem("Translate selected text", R.drawable.ic_translate) {
            val url = "https://translate.google.com/?sl=auto&tl=en&text=${URLEncoder.encode(selectedText, "UTF-8")}&op=translate"
            activity.addNewTab(url)
        }

        addItem("Explain selected text", R.drawable.ic_sparkles, isHighlight = true) {
            showExplainDialog(activity, selectedText)
        }

        addItem("Share selected text", R.drawable.ic_share) {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, selectedText)
            }
            activity.startActivity(Intent.createChooser(intent, "Share text"))
        }

        addItem("Save selection", R.drawable.ic_reading_mode) {
            val tab = activity.getActiveTab()
            val pageTitle = tab?.title ?: "Web Snippet"
            val pageUrl = tab?.url ?: ""
            HazeDatabase.getInstance(activity).addSavedSelection(pageTitle, pageUrl, selectedText)
            Toast.makeText(activity, "Selection saved to Snippets!", Toast.LENGTH_SHORT).show()
        }

        addItem("Read aloud", R.drawable.ic_volume_up) {
            speakText(activity, selectedText)
        }

        dialog.setContentView(root)
        dialog.show()
    }

    fun showExplainDialog(activity: MainActivity, textContext: String) {
        showAiCopilotDialog(activity, textContext)
    }

    // ==========================================
    // AI COPILOT DIALOG
    // ==========================================
    fun showAiCopilotDialog(activity: MainActivity, textContext: String) {
        val dialog = BottomSheetDialog(activity)
        val isDark = activity.getSharedPreferences("HazePrefs", Context.MODE_PRIVATE).getBoolean("is_dark", false)
        val accentColor = Color.parseColor(activity.getSharedPreferences("HazePrefs", Context.MODE_PRIVATE).getString("accent_color", "#0A56D0"))
        val bgColor = if (isDark) Color.parseColor("#1E1E1E") else Color.parseColor("#FFFFFF")
        val textColor = if (isDark) Color.WHITE else Color.parseColor("#202124")
        val subTextColor = if (isDark) Color.parseColor("#9AA0A6") else Color.parseColor("#5F6368")

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgColor)
            setPadding(dpToPx(activity, 20), dpToPx(activity, 20), dpToPx(activity, 20), dpToPx(activity, 24))
        }

        val header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dpToPx(activity, 14))
        }
        val icon = ImageView(activity).apply {
            setImageResource(R.drawable.ic_sparkles)
            setColorFilter(accentColor)
            layoutParams = LinearLayout.LayoutParams(dpToPx(activity, 26), dpToPx(activity, 26)).apply {
                marginEnd = dpToPx(activity, 10)
            }
        }
        val title = TextView(activity).apply {
            text = "Haze AI Copilot"
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setTextColor(textColor)
        }
        header.addView(icon)
        header.addView(title)
        root.addView(header)

        // Pill Quick Actions
        val pills = HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            setPadding(0, 0, 0, dpToPx(activity, 14))
        }
        val pillRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }

        val resultBox = TextView(activity).apply {
            text = "Select an action below or ask AI anything about this text."
            textSize = 14f
            setTextColor(textColor)
            setLineSpacing(4f, 1.2f)
            setPadding(dpToPx(activity, 14), dpToPx(activity, 14), dpToPx(activity, 14), dpToPx(activity, 14))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(activity, 12).toFloat()
                setColor(if (isDark) Color.parseColor("#2A2A2A") else Color.parseColor("#F1F3F4"))
            }
        }

        fun generateAiResponse(action: String) {
            activity.triggerStrongHaptic()
            when (action) {
                "Summarize" -> {
                    val summary = if (textContext.length > 120) {
                        "📌 **Summary:**\n• " + textContext.split(". ").take(3).joinToString("\n• ")
                    } else {
                        "📌 **Summary:**\n$textContext"
                    }
                    resultBox.text = summary
                }
                "Key Points" -> {
                    val points = textContext.split(" ").chunked(12).map { it.joinToString(" ") }.take(4)
                    resultBox.text = "💡 **Key Takeaways:**\n" + points.mapIndexed { idx, s -> "${idx + 1}. $s..." }.joinToString("\n")
                }
                "Explain Simple" -> {
                    resultBox.text = "🧒 **Simplified Explanation:**\nIn plain words, this text discusses: \"${textContext.take(160)}...\". It highlights essential information clearly and concisely."
                }
                "Translate" -> {
                    resultBox.text = "🌐 **Translation Preview:**\nOpening full translation in Google Translate..."
                    val url = "https://translate.google.com/?sl=auto&tl=en&text=${URLEncoder.encode(textContext, "UTF-8")}&op=translate"
                    activity.addNewTab(url)
                    dialog.dismiss()
                }
            }
        }

        fun addPill(label: String) {
            val tv = TextView(activity).apply {
                text = label
                textSize = 12f
                setTypeface(null, Typeface.BOLD)
                setTextColor(accentColor)
                setPadding(dpToPx(activity, 14), dpToPx(activity, 8), dpToPx(activity, 14), dpToPx(activity, 8))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.RECTANGLE
                    cornerRadius = dpToPx(activity, 18).toFloat()
                    setColor(if (isDark) Color.parseColor("#1A2B4C") else Color.parseColor("#E8F0FE"))
                }
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = dpToPx(activity, 8)
                }
                setOnClickListener { generateAiResponse(label) }
            }
            pillRow.addView(tv)
        }

        addPill("Summarize")
        addPill("Key Points")
        addPill("Explain Simple")
        addPill("Translate")

        pills.addView(pillRow)
        root.addView(pills)
        root.addView(resultBox)

        dialog.setContentView(root)
        dialog.show()
    }

    private fun showQrCodeDialog(activity: MainActivity, url: String) {
        val qrUrl = "https://api.qrserver.com/v1/create-qr-code/?size=300x300&data=${URLEncoder.encode(url, "UTF-8")}"
        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dpToPx(activity, 24), dpToPx(activity, 20), dpToPx(activity, 24), dpToPx(activity, 20))
        }

        val webView = WebView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(dpToPx(activity, 220), dpToPx(activity, 220))
            loadUrl(qrUrl)
        }
        val urlTv = TextView(activity).apply {
            text = url
            textSize = 12f
            setPadding(0, dpToPx(activity, 12), 0, 0)
            setTextColor(Color.GRAY)
            isSingleLine = true
        }

        layout.addView(webView)
        layout.addView(urlTv)

        AlertDialog.Builder(activity)
            .setTitle("QR Code for Page")
            .setView(layout)
            .setPositiveButton("Done", null)
            .show()
    }

    private fun dpToPx(context: Context, dp: Int): Int {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp.toFloat(), context.resources.displayMetrics).toInt()
    }

    private fun getSelectableRes(context: Context): Int {
        val out = TypedValue()
        context.theme.resolveAttribute(android.R.attr.selectableItemBackground, out, true)
        return out.resourceId
    }
}
