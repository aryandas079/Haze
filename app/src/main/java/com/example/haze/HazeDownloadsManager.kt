package com.example.haze

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Environment
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.URLUtil
import android.webkit.WebView
import android.widget.*
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.*

object HazeDownloadsManager {

    fun getDisplayPathFromUri(context: Context, uriStr: String?): String {
        if (uriStr.isNullOrEmpty()) return "Downloads"
        return try {
            val uri = Uri.parse(uriStr)
            if ("content" == uri.scheme) {
                val docId = android.provider.DocumentsContract.getTreeDocumentId(uri)
                val parts = docId.split(":")
                if (parts.size >= 2) {
                    val relativePath = parts[1]
                    if (relativePath.isNotEmpty()) "Downloads/$relativePath" else "Downloads"
                } else "Downloads"
            } else if ("file" == uri.scheme) {
                val path = uri.path ?: ""
                if (path.isNotEmpty()) path.substringAfterLast("/") else "Downloads"
            } else "Downloads"
        } catch (_: Exception) {
            "Downloads"
        }
    }

    fun downloadDataUri(
        activity: MainActivity,
        dataUri: String,
        suggestedName: String? = null,
        mimeType: String? = null
    ) {
        Thread {
            try {
                val header = dataUri.substringBefore(",", "")
                val base64Data = dataUri.substringAfter(",", "")
                val detectedMime = if (header.contains(":") && header.contains(";")) {
                    header.substringAfter(":").substringBefore(";")
                } else {
                    mimeType?.ifEmpty { null } ?: "image/jpeg"
                }
                val bytes = android.util.Base64.decode(base64Data, android.util.Base64.DEFAULT)
                val ext = HazeFileNameResolver.getExtensionFromMime(detectedMime) ?: "jpg"
                val rawName = suggestedName?.ifEmpty { null } ?: "image_${System.currentTimeMillis()}.$ext"
                val finalFileName = if (rawName.contains(".")) rawName else "$rawName.$ext"

                val destDir = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: activity.filesDir
                val localFile = File(destDir, finalFileName)
                localFile.outputStream().use { it.write(bytes) }

                HazeDownloadEngine.exportToPublicDownloads(activity, localFile, finalFileName, detectedMime)

                val downloadId = System.currentTimeMillis() + (0..999).random()
                HazeDatabase.getInstance(activity).addDownload(
                    downloadId = downloadId,
                    fileName = finalFileName,
                    filePath = localFile.absolutePath,
                    fileSize = bytes.size.toLong(),
                    url = "data:$detectedMime",
                    mimeType = detectedMime,
                    status = HazeDownloadEngine.STATUS_COMPLETED
                )

                activity.runOnUiThread {
                    activity.triggerStrongHaptic()
                    Toast.makeText(activity, "Download completed: $finalFileName", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                activity.runOnUiThread {
                    Toast.makeText(activity, "Download failed: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    fun downloadBlobUrl(activity: MainActivity, blobUrl: String, mimeType: String = "*/*", suggestedName: String? = null) {
        val webView = activity.getActiveTab()?.browserTab?.renderView as? WebView ?: return
        val name = suggestedName ?: "file_${System.currentTimeMillis()}"
        val js = """
            (function() {
                fetch('$blobUrl')
                    .then(function(r) { return r.blob(); })
                    .then(function(blob) {
                        var reader = new FileReader();
                        reader.onloadend = function() {
                            if (window.Android && window.Android.onBlobDownloaded) {
                                window.Android.onBlobDownloaded(reader.result, '$name', blob.type || '$mimeType');
                            }
                        };
                        reader.readAsDataURL(blob);
                    })
                    .catch(function(err) {
                        console.error('Blob download failed', err);
                    });
            })();
        """.trimIndent()
        activity.runOnUiThread {
            webView.evaluateJavascript(js, null)
        }
    }

    fun handleYouTubeLinkDownload(activity: MainActivity, youtubeUrl: String) {
        val videoId = HazeFileNameResolver.extractYouTubeVideoId(youtubeUrl)
        if (videoId.isNullOrEmpty()) {
            val defaultName = HazeFileNameResolver.resolveBestFileName(youtubeUrl)
            initDownload(activity, youtubeUrl, "", "", "video/mp4", -1, false, defaultName)
            return
        }

        val isDark = activity.getSharedPreferences("HazePrefs", Context.MODE_PRIVATE).getBoolean("is_dark", false) || activity.isPrivateMode
        val accentColor = Color.parseColor(activity.getSharedPreferences("HazePrefs", Context.MODE_PRIVATE).getString("accent_color", "#0A56D0"))
        val bgColor = if (isDark) Color.parseColor("#1E1E1E") else Color.parseColor("#FFFFFF")
        val textColor = if (isDark) Color.WHITE else Color.parseColor("#202124")
        val subTextColor = if (isDark) Color.parseColor("#9AA0A6") else Color.parseColor("#5F6368")

        val bottomSheet = BottomSheetDialog(activity)
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgColor)
            setPadding(dpToPx(activity, 20), dpToPx(activity, 16), dpToPx(activity, 20), dpToPx(activity, 24))
        }

        val titleTv = TextView(activity).apply {
            text = "YouTube Media Downloader"
            textSize = 17f
            setTypeface(null, Typeface.BOLD)
            setTextColor(textColor)
            setPadding(0, 0, 0, dpToPx(activity, 8))
        }
        root.addView(titleTv)

        val statusTv = TextView(activity).apply {
            text = "Resolving video information..."
            textSize = 13f
            setTextColor(subTextColor)
            setPadding(0, 0, 0, dpToPx(activity, 16))
        }
        root.addView(statusTv)

        val progress = ProgressBar(activity).apply {
            isIndeterminate = true
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dpToPx(activity, 16)
            }
        }
        root.addView(progress)

        val actionsContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        root.addView(actionsContainer)

        bottomSheet.setContentView(root)
        bottomSheet.show()

        Thread {
            var resolvedTitle: String? = null
            try {
                val oembedUrl = "https://www.youtube.com/oembed?url=https://www.youtube.com/watch?v=$videoId&format=json"
                val conn = URL(oembedUrl).openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                if (conn.responseCode == 200) {
                    val jsonStr = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(jsonStr)
                    resolvedTitle = json.optString("title").ifEmpty { null }
                }
                conn.disconnect()
            } catch (_: Exception) {}

            val finalTitle = resolvedTitle ?: "YouTube Video ($videoId)"
            val safeTitle = HazeFileNameResolver.sanitizeFileName(finalTitle)

            val pipedInstances = listOf(
                "https://api.piped.private.coffee",
                "https://pipedapi.kavin.rocks",
                "https://pipedapi.adminforge.de",
                "https://pipedapi.leptons.xyz"
            )

            var bestVideoUrl: String? = null
            var bestAudioUrl: String? = null

            for (instance in pipedInstances) {
                try {
                    val streamEndpoint = "$instance/streams/$videoId"
                    val conn = URL(streamEndpoint).openConnection() as HttpURLConnection
                    conn.connectTimeout = 5000
                    conn.readTimeout = 7000
                    if (conn.responseCode == 200) {
                        val jsonStr = conn.inputStream.bufferedReader().use { it.readText() }
                        val json = JSONObject(jsonStr)

                        val videoStreams = json.optJSONArray("videoStreams")
                        if (videoStreams != null) {
                            for (i in 0 until videoStreams.length()) {
                                val item = videoStreams.optJSONObject(i) ?: continue
                                val vUrl = item.optString("url")
                                val format = item.optString("format", "").lowercase()
                                val videoOnly = item.optBoolean("videoOnly", false)
                                if (vUrl.isNotEmpty() && (format == "mp4" || !videoOnly)) {
                                    bestVideoUrl = vUrl
                                    break
                                }
                            }
                        }

                        val audioStreams = json.optJSONArray("audioStreams")
                        if (audioStreams != null) {
                            for (i in 0 until audioStreams.length()) {
                                val item = audioStreams.optJSONObject(i) ?: continue
                                val aUrl = item.optString("url")
                                if (aUrl.isNotEmpty()) {
                                    bestAudioUrl = aUrl
                                    break
                                }
                            }
                        }

                        if (bestVideoUrl != null || bestAudioUrl != null) {
                            conn.disconnect()
                            break
                        }
                    }
                    conn.disconnect()
                } catch (_: Exception) {}
            }

            activity.runOnUiThread {
                progress.visibility = View.GONE
                statusTv.text = finalTitle
                actionsContainer.visibility = View.VISIBLE

                fun addActionBtn(btnText: String, onClick: () -> Unit) {
                    val btn = Button(activity).apply {
                        text = btnText
                        setTextColor(Color.WHITE)
                        setBackgroundColor(accentColor)
                        val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(activity, 46)).apply {
                            bottomMargin = dpToPx(activity, 10)
                        }
                        layoutParams = params
                        setOnClickListener {
                            activity.triggerStrongHaptic()
                            bottomSheet.dismiss()
                            onClick()
                        }
                    }
                    actionsContainer.addView(btn)
                }

                val ua = (activity.getActiveTab()?.browserTab?.renderView as? WebView)?.settings?.userAgentString ?: ""

                if (bestVideoUrl != null) {
                    addActionBtn("Download Video (MP4)") {
                        initDownload(
                            activity = activity,
                            url = bestVideoUrl!!,
                            userAgent = ua,
                            contentDisposition = "",
                            mimeType = "video/mp4",
                            contentLength = -1,
                            isPrivate = false,
                            suggestedFileName = "$safeTitle.mp4"
                        )
                    }
                }

                if (bestAudioUrl != null) {
                    addActionBtn("Download Audio (M4A / MP3)") {
                        initDownload(
                            activity = activity,
                            url = bestAudioUrl!!,
                            userAgent = ua,
                            contentDisposition = "",
                            mimeType = "audio/mp4",
                            contentLength = -1,
                            isPrivate = false,
                            suggestedFileName = "$safeTitle.m4a"
                        )
                    }
                }

                // Fallback action button
                val outlineBtn = Button(activity).apply {
                    text = "Open in Video Downloader Web"
                    setTextColor(textColor)
                    setBackgroundColor(if (isDark) Color.parseColor("#333333") else Color.parseColor("#EAEAEA"))
                    val params = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(activity, 44))
                    layoutParams = params
                    setOnClickListener {
                        activity.triggerStrongHaptic()
                        bottomSheet.dismiss()
                        val helperUrl = "https://y2mate.nu/en1/?q=" + URLEncoder.encode("https://www.youtube.com/watch?v=$videoId", "UTF-8")
                        activity.addNewTab(helperUrl)
                    }
                }
                actionsContainer.addView(outlineBtn)
            }
        }.start()
    }

    fun initDownload(
        activity: MainActivity,
        url: String,
        userAgent: String,
        contentDisposition: String,
        mimeType: String,
        contentLength: Long,
        isPrivate: Boolean = false,
        suggestedFileName: String? = null
    ) {
        if (url.startsWith("data:")) {
            downloadDataUri(activity, url, suggestedFileName, mimeType)
            return
        }
        if (url.startsWith("blob:")) {
            downloadBlobUrl(activity, url, mimeType, suggestedFileName)
            return
        }
        if (HazeFileNameResolver.isYouTubeUrl(url)) {
            handleYouTubeLinkDownload(activity, url)
            return
        }

        val prefs = activity.getSharedPreferences("HazePrefs", Context.MODE_PRIVATE)
        val defaultFileName = HazeFileNameResolver.resolveBestFileName(url, contentDisposition, mimeType, suggestedFileName)
        val sizeFormatted = formatFileSize(contentLength)
        val askBeforeDownload = prefs.getBoolean("download_ask_location", false)
        
        var selectedCustomUri: String? = prefs.getString("custom_download_dir_uri", null)
        val hostStr = try { Uri.parse(url).host ?: url } catch (_: Exception) { url }

        val finalUserAgent = if (userAgent.isNotBlank()) userAgent else {
            (activity.getActiveTab()?.browserTab?.renderView as? WebView)?.settings?.userAgentString ?: ""
        }

        if (askBeforeDownload || isPrivate) {
            val isDark = prefs.getBoolean("is_dark", false) || activity.isPrivateMode
            val textColor = if (isDark) Color.WHITE else Color.parseColor("#202124")
            val subTextColor = if (isDark) Color.parseColor("#9AA0A6") else Color.parseColor("#5F6368")
            val accentColor = Color.parseColor(prefs.getString("accent_color", "#1A73E8") ?: "#1A73E8")

            val dialogView = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dpToPx(activity, 20), dpToPx(activity, 16), dpToPx(activity, 20), dpToPx(activity, 16))
            }

            val tvTitle = TextView(activity).apply {
                text = "Download File"
                textSize = 18f
                setTypeface(null, Typeface.BOLD)
                setTextColor(textColor)
                setPadding(0, 0, 0, dpToPx(activity, 8))
            }
            dialogView.addView(tvTitle)

            val tvSub = TextView(activity).apply {
                text = "Size: $sizeFormatted • From: $hostStr"
                textSize = 12f
                setTextColor(subTextColor)
                setPadding(0, 0, 0, dpToPx(activity, 12))
            }
            dialogView.addView(tvSub)

            // Filename Input
            val tvFileLabel = TextView(activity).apply {
                text = "File Name"
                textSize = 12f
                setTypeface(null, Typeface.BOLD)
                setTextColor(subTextColor)
                setPadding(0, 0, 0, dpToPx(activity, 4))
            }
            dialogView.addView(tvFileLabel)

            val etFileName = EditText(activity).apply {
                setText(defaultFileName)
                textSize = 14f
                setTextColor(textColor)
                setSingleLine()
                background = GradientDrawable().apply {
                    setColor(if (isDark) Color.parseColor("#2D2D2D") else Color.parseColor("#F1F3F4"))
                    cornerRadius = dpToPx(activity, 8).toFloat()
                }
                setPadding(dpToPx(activity, 12), dpToPx(activity, 10), dpToPx(activity, 12), dpToPx(activity, 10))
            }
            dialogView.addView(etFileName)

            // Location Picker Row
            val locationRow = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dpToPx(activity, 14), 0, dpToPx(activity, 10))
                isClickable = true
                isFocusable = true
            }

            val tvFolderLabel = TextView(activity).apply {
                text = "Save To: "
                textSize = 13f
                setTypeface(null, Typeface.BOLD)
                setTextColor(subTextColor)
            }
            val tvFolderName = TextView(activity).apply {
                text = getDisplayPathFromUri(activity, selectedCustomUri)
                textSize = 13f
                setTextColor(accentColor)
                setTypeface(null, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val btnChangeFolder = TextView(activity).apply {
                text = "Change"
                textSize = 13f
                setTypeface(null, Typeface.BOLD)
                setTextColor(accentColor)
            }
            locationRow.addView(tvFolderLabel)
            locationRow.addView(tvFolderName)
            locationRow.addView(btnChangeFolder)
            dialogView.addView(locationRow)

            locationRow.setOnClickListener {
                activity.triggerStrongHaptic()
                activity.onCustomDirSelected = { uri ->
                    selectedCustomUri = uri.toString()
                    tvFolderName.text = getDisplayPathFromUri(activity, selectedCustomUri)
                }
                activity.downloadDirPickerLauncher.launch(null)
            }

            if (isPrivate) {
                val tvWarn = TextView(activity).apply {
                    text = "WARNING: Downloaded files remain on your device after closing Private Mode."
                    textSize = 11f
                    setTextColor(Color.parseColor("#D93025"))
                    setPadding(0, dpToPx(activity, 4), 0, dpToPx(activity, 8))
                }
                dialogView.addView(tvWarn)
            }

            AlertDialog.Builder(activity)
                .setView(dialogView)
                .setPositiveButton("Download") { _, _ ->
                    val finalFileName = etFileName.text.toString().trim().ifEmpty { defaultFileName }
                    activity.triggerStrongHaptic()
                    HazeDownloadEngine.enqueueDownload(
                        context = activity,
                        url = url,
                        userAgent = finalUserAgent,
                        contentDisposition = contentDisposition,
                        mimeType = mimeType,
                        contentLength = contentLength,
                        suggestedFileName = finalFileName,
                        isPrivate = isPrivate,
                        customTargetDirUri = selectedCustomUri
                    )
                    Toast.makeText(activity, "Download added: $finalFileName", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        } else {
            // Auto-start download directly with defaults
            activity.triggerStrongHaptic()
            HazeDownloadEngine.enqueueDownload(
                context = activity,
                url = url,
                userAgent = finalUserAgent,
                contentDisposition = contentDisposition,
                mimeType = mimeType,
                contentLength = contentLength,
                suggestedFileName = defaultFileName,
                isPrivate = isPrivate,
                customTargetDirUri = selectedCustomUri
            )
            Toast.makeText(activity, "Download added: $defaultFileName", Toast.LENGTH_SHORT).show()
        }
    }

    fun showDownloadsDialog(activity: MainActivity) {
        val isDark = activity.getSharedPreferences("HazePrefs", Context.MODE_PRIVATE).getBoolean("is_dark", false) || activity.isPrivateMode
        val accentColor = Color.parseColor(activity.getSharedPreferences("HazePrefs", Context.MODE_PRIVATE).getString("accent_color", "#0A56D0"))
        val bgColor = if (isDark) Color.parseColor("#121212") else Color.parseColor("#FFFFFF")
        val surfaceColor = if (isDark) Color.parseColor("#1E1E1E") else Color.parseColor("#FFFFFF")
        val textColor = if (isDark) Color.parseColor("#E3E3E3") else Color.parseColor("#1F1F1F")
        val subTextColor = if (isDark) Color.parseColor("#9E9E9E") else Color.parseColor("#757575")
        val strokeColor = if (isDark) Color.parseColor("#2C2C2C") else Color.parseColor("#E0E0E0")

        val dialog = Dialog(activity, android.R.style.Theme_Light_NoTitleBar)
        dialog.window?.setWindowAnimations(R.style.HazeDialogAnimation)
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(bgColor))
        dialog.window?.statusBarColor = bgColor
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            dialog.window?.navigationBarColor = bgColor
        }

        var activeFilter = "ALL" // ALL, ACTIVE, COMPLETED, FAILED

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgColor)
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        // --- Header Bar ---
        val header = RelativeLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(activity, 64))
            setPadding(dpToPx(activity, 16), 0, dpToPx(activity, 16), 0)
        }

        val btnBack = ImageButton(activity).apply {
            id = View.generateViewId()
            setImageResource(R.drawable.ic_close)
            setColorFilter(textColor)
            setBackgroundResource(getSelectableRes(activity))
            layoutParams = RelativeLayout.LayoutParams(dpToPx(activity, 40), dpToPx(activity, 40)).apply {
                addRule(RelativeLayout.ALIGN_PARENT_END)
                addRule(RelativeLayout.CENTER_VERTICAL)
            }
            setOnClickListener {
                activity.triggerStrongHaptic(it)
                dialog.dismiss()
            }
        }
        
        val btnSettings = ImageButton(activity).apply {
            id = View.generateViewId()
            setImageResource(R.drawable.ic_settings)
            setColorFilter(textColor)
            setBackgroundResource(getSelectableRes(activity))
            layoutParams = RelativeLayout.LayoutParams(dpToPx(activity, 40), dpToPx(activity, 40)).apply {
                addRule(RelativeLayout.LEFT_OF, btnBack.id)
                addRule(RelativeLayout.CENTER_VERTICAL)
                marginEnd = dpToPx(activity, 4)
            }
            setOnClickListener {
                activity.triggerStrongHaptic(it)
                showDownloadSettingsDialog(activity, surfaceColor, textColor, subTextColor, strokeColor)
            }
        }
        
        val btnFilter = ImageView(activity).apply {
            id = View.generateViewId()
            setImageResource(R.drawable.ic_chevron_down)
            setColorFilter(textColor)
            setBackgroundResource(getSelectableRes(activity))
            setPadding(dpToPx(activity, 8), dpToPx(activity, 8), dpToPx(activity, 8), dpToPx(activity, 8))
            layoutParams = RelativeLayout.LayoutParams(dpToPx(activity, 40), dpToPx(activity, 40)).apply {
                addRule(RelativeLayout.LEFT_OF, btnSettings.id)
                addRule(RelativeLayout.CENTER_VERTICAL)
                marginEnd = dpToPx(activity, 4)
            }
        }
        
        var refreshListProxy: (() -> Unit)? = null

        btnFilter.setOnClickListener {
            val popup = android.widget.PopupMenu(activity, btnFilter)
            val categories = listOf("ALL", "ACTIVE", "COMPLETED", "FAILED")
            for (cat in categories) {
                val count = HazeDatabase.getInstance(activity).getAllDownloads(cat).size
                val label = when (cat) {
                    "ALL" -> "All"
                    "ACTIVE" -> "Active"
                    "COMPLETED" -> "Completed"
                    "FAILED" -> "Failed"
                    else -> cat
                }
                popup.menu.add(android.view.Menu.NONE, categories.indexOf(cat), android.view.Menu.NONE, if (count > 0) "$label ($count)" else label)
            }
            popup.setOnMenuItemClickListener { item ->
                activeFilter = categories[item.itemId]
                refreshListProxy?.invoke()
                true
            }
            popup.show()
        }

        header.addView(btnBack)
        header.addView(btnSettings)
        header.addView(btnFilter)

        val tvTitle = TextView(activity).apply {
            text = "Downloads"
            textSize = 20f
            setTypeface(null, Typeface.NORMAL)
            setTextColor(textColor)
            layoutParams = RelativeLayout.LayoutParams(RelativeLayout.LayoutParams.WRAP_CONTENT, RelativeLayout.LayoutParams.WRAP_CONTENT).apply {
                addRule(RelativeLayout.ALIGN_PARENT_START)
                addRule(RelativeLayout.CENTER_VERTICAL)
            }
        }
        header.addView(tvTitle)
        
        val headerDivider = View(activity).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
            setBackgroundColor(strokeColor)
            alpha = 0.5f
        }
        
        fun calculateStorageString(): String {
            return try {
                val dir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                if (dir.exists()) {
                    var totalBytes = 0L
                    dir.listFiles()?.forEach { if (it.isFile) totalBytes += it.length() }
                    val totalSpaceBytes = android.os.Environment.getExternalStorageDirectory().totalSpace
                    "Using ${formatFileSize(totalBytes)} of ${formatFileSize(totalSpaceBytes)}"
                } else {
                    "Using 0.00 KB of 64.00 GB"
                }
            } catch (e: Exception) {
                "Using 0.00 KB of 64.00 GB"
            }
        }
        val tvStorage = TextView(activity).apply {
            text = calculateStorageString()
            textSize = 10f
            setTextColor(subTextColor)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(dpToPx(activity, 16), dpToPx(activity, 4), dpToPx(activity, 16), dpToPx(activity, 12))
            }
        }
        
        val topContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        topContainer.addView(header)
        topContainer.addView(tvStorage)
        topContainer.addView(headerDivider)
        root.addView(topContainer)

        // --- Category Tabs ---
        // Removed

        // --- Recycler & Empty View ---
        val contentFrame = FrameLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }

        val emptyView = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT).apply {
                gravity = Gravity.CENTER
            }
            setPadding(dpToPx(activity, 32), 0, dpToPx(activity, 32), dpToPx(activity, 60))

            val emptyIconContainer = FrameLayout(activity).apply {
                layoutParams = LinearLayout.LayoutParams(dpToPx(activity, 120), dpToPx(activity, 120)).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    bottomMargin = dpToPx(activity, 24)
                }
                
                val bgBlob = ImageView(activity).apply {
                    setImageResource(R.drawable.bg_download_blob) // You will need to create this shape or use a generic one
                    setColorFilter(if (isDark) Color.parseColor("#1B2943") else Color.parseColor("#D3E3FD"))
                    layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
                }
                
                val icon = ImageView(activity).apply {
                    setImageResource(R.drawable.ic_download)
                    setColorFilter(accentColor)
                    layoutParams = FrameLayout.LayoutParams(dpToPx(activity, 48), dpToPx(activity, 48)).apply {
                        gravity = Gravity.CENTER
                    }
                }
                
                addView(bgBlob)
                addView(icon)
            }

            val emptyTitle = TextView(activity).apply {
                text = "You'll find your downloads here"
                textSize = 16f
                setTypeface(null, Typeface.NORMAL)
                setTextColor(textColor)
                gravity = Gravity.CENTER
                textAlignment = View.TEXT_ALIGNMENT_CENTER
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    bottomMargin = dpToPx(activity, 12)
                }
            }
            val emptySub = TextView(activity).apply {
                text = "You can save images and files to view offline or share in other apps"
                textSize = 13f
                setTextColor(subTextColor)
                gravity = Gravity.CENTER
                textAlignment = View.TEXT_ALIGNMENT_CENTER
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                }
            }
            addView(emptyIconContainer)
            addView(emptyTitle)
            addView(emptySub)
        }
        contentFrame.addView(emptyView)

        val recyclerView = RecyclerView(activity).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            layoutManager = LinearLayoutManager(activity)
        }
        contentFrame.addView(recyclerView)

        root.addView(contentFrame)

        var adapter: DownloadsRecyclerAdapter? = null

        fun refreshList() {
            val db = HazeDatabase.getInstance(activity)
            val list = db.getAllDownloads(activeFilter)

            if (list.isEmpty()) {
                emptyView.visibility = View.VISIBLE
                recyclerView.visibility = View.GONE
            } else {
                emptyView.visibility = View.GONE
                recyclerView.visibility = View.VISIBLE
                adapter = DownloadsRecyclerAdapter(
                    activity = activity,
                    list = list,
                    onItemClick = { item ->
                        if (item.status == HazeDownloadEngine.STATUS_COMPLETED) {
                            openDownloadedFile(activity, item)
                        } else if (item.status == HazeDownloadEngine.STATUS_PAUSED) {
                            activity.triggerStrongHaptic()
                            HazeDownloadEngine.resumeDownload(activity, item.downloadId)
                            refreshList()
                        } else if (item.status == HazeDownloadEngine.STATUS_DOWNLOADING) {
                            activity.triggerStrongHaptic()
                            HazeDownloadEngine.pauseDownload(item.downloadId)
                            refreshList()
                        } else if (item.status == HazeDownloadEngine.STATUS_FAILED) {
                            activity.triggerStrongHaptic()
                            HazeDownloadEngine.retryDownload(activity, item.downloadId)
                            refreshList()
                        }
                    },
                    onItemOptions = { item ->
                        activity.triggerStrongHaptic()
                        showDownloadItemOptions(activity, item) { refreshList() }
                    },
                    onPauseClick = { item ->
                        activity.triggerStrongHaptic()
                        HazeDownloadEngine.pauseDownload(item.downloadId)
                        refreshList()
                    },
                    onResumeClick = { item ->
                        activity.triggerStrongHaptic()
                        HazeDownloadEngine.resumeDownload(activity, item.downloadId)
                        refreshList()
                    },
                    onCancelClick = { item ->
                        activity.triggerStrongHaptic()
                        HazeDownloadEngine.cancelDownload(item.downloadId)
                        refreshList()
                    },
                    onRetryClick = { item ->
                        activity.triggerStrongHaptic()
                        HazeDownloadEngine.retryDownload(activity, item.downloadId)
                        refreshList()
                    },
                    isDark = isDark,
                    surfaceColor = surfaceColor,
                    textColor = textColor,
                    subTextColor = subTextColor,
                    accentColor = accentColor,
                    strokeColor = strokeColor
                )
                recyclerView.adapter = adapter
            }

            // Removed old Category Tabs logic
        }

        refreshListProxy = { refreshList() }

        // Register Real-Time Progress Listener
        val downloadListener = object : DownloadProgressListener {
            override fun onProgressUpdate(info: DownloadProgressInfo) {
                adapter?.updateItemProgress(info)
            }

            override fun onStatusChange(info: DownloadProgressInfo) {
                refreshList()
            }
        }
        HazeDownloadEngine.addListener(downloadListener)
        dialog.setOnDismissListener {
            HazeDownloadEngine.removeListener(downloadListener)
        }

        refreshList()
        dialog.setContentView(root)
        dialog.show()
    }

    private fun openDownloadedFile(activity: MainActivity, item: DownloadItem) {
        val file = File(item.filePath)
        if (!file.exists()) {
            Toast.makeText(activity, "File not found: ${file.name}", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, item.mimeType.ifEmpty { "*/*" })
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(activity, "No app found to open this file", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showDownloadItemOptions(activity: MainActivity, item: DownloadItem, onRefresh: () -> Unit) {
        val options = arrayOf(
            "Open file",
            "Share file",
            "Rename file",
            "Remove from list",
            "Delete file from device"
        )

        AlertDialog.Builder(activity)
            .setTitle(item.fileName)
            .setItems(options) { _, which ->
                activity.triggerStrongHaptic()
                when (which) {
                    0 -> openDownloadedFile(activity, item)
                    1 -> { // Share
                        val file = File(item.filePath)
                        if (file.exists()) {
                            val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", file)
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = item.mimeType.ifEmpty { "*/*" }
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            activity.startActivity(Intent.createChooser(intent, "Share file"))
                        } else {
                            Toast.makeText(activity, "File does not exist", Toast.LENGTH_SHORT).show()
                        }
                    }
                    2 -> { // Rename
                        val input = EditText(activity).apply {
                            setText(item.fileName)
                            setSingleLine()
                        }
                        val container = FrameLayout(activity).apply {
                            setPadding(dpToPx(activity, 24), dpToPx(activity, 16), dpToPx(activity, 24), dpToPx(activity, 8))
                            addView(input)
                        }
                        AlertDialog.Builder(activity)
                            .setTitle("Rename Download")
                            .setView(container)
                            .setPositiveButton("Save") { _, _ ->
                                val newName = input.text.toString().trim()
                                if (newName.isNotEmpty() && newName != item.fileName) {
                                    val oldFile = File(item.filePath)
                                    val newFile = File(oldFile.parentFile, newName)
                                    if (oldFile.exists()) oldFile.renameTo(newFile)
                                    HazeDatabase.getInstance(activity).renameDownload(item.id, newName)
                                    HazeDatabase.getInstance(activity).updateDownloadStatus(item.downloadId, item.status, newFile.absolutePath)
                                    Toast.makeText(activity, "Renamed to $newName", Toast.LENGTH_SHORT).show()
                                    onRefresh()
                                }
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                    3 -> { // Remove from list only
                        HazeDatabase.getInstance(activity).deleteDownload(item.id)
                        Toast.makeText(activity, "Removed from list", Toast.LENGTH_SHORT).show()
                        onRefresh()
                    }
                    4 -> { // Delete file & record
                        val file = File(item.filePath)
                        if (file.exists()) file.delete()
                        HazeDatabase.getInstance(activity).deleteDownload(item.id)
                        Toast.makeText(activity, "File deleted", Toast.LENGTH_SHORT).show()
                        onRefresh()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // --- Recycler Adapter with Real-Time Updates ---

    private class DownloadsRecyclerAdapter(
        private val activity: MainActivity,
        private val list: List<DownloadItem>,
        private val onItemClick: (DownloadItem) -> Unit,
        private val onItemOptions: (DownloadItem) -> Unit,
        private val onPauseClick: (DownloadItem) -> Unit,
        private val onResumeClick: (DownloadItem) -> Unit,
        private val onCancelClick: (DownloadItem) -> Unit,
        private val onRetryClick: (DownloadItem) -> Unit,
        private val isDark: Boolean,
        private val surfaceColor: Int,
        private val textColor: Int,
        private val subTextColor: Int,
        private val accentColor: Int,
        private val strokeColor: Int
    ) : RecyclerView.Adapter<DownloadsRecyclerAdapter.DownloadViewHolder>() {

        private val timeFormat = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())

        fun updateItemProgress(info: DownloadProgressInfo) {
            val index = list.indexOfFirst { it.downloadId == info.downloadId }
            if (index != -1) {
                notifyItemChanged(index, info)
            }
        }

        class DownloadViewHolder(
            val view: View,
            val iconView: ImageView,
            val titleTv: TextView,
            val subTv: TextView,
            val speedTv: TextView,
            val progressBar: ProgressBar,
            val statusBadge: TextView,
            val btnAction: ImageButton,
            val btnOptions: ImageButton
        ) : RecyclerView.ViewHolder(view)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DownloadViewHolder {
            val card = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = ViewGroup.MarginLayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    setMargins(dpToPx(activity, 16), dpToPx(activity, 4), dpToPx(activity, 16), dpToPx(activity, 6))
                }
                background = GradientDrawable().apply {
                    cornerRadius = dpToPx(activity, 16).toFloat()
                    setColor(surfaceColor)
                    setStroke(dpToPx(activity, 1), strokeColor)
                }
                setPadding(dpToPx(activity, 14), dpToPx(activity, 12), dpToPx(activity, 14), dpToPx(activity, 12))
            }

            val topRow = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            val iconView = ImageView(activity).apply {
                layoutParams = LinearLayout.LayoutParams(dpToPx(activity, 32), dpToPx(activity, 32)).apply {
                    marginEnd = dpToPx(activity, 12)
                }
            }

            val infoCol = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val titleTv = TextView(activity).apply {
                textSize = 14f
                setTypeface(null, Typeface.BOLD)
                setTextColor(textColor)
                isSingleLine = true
            }

            val subRow = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dpToPx(activity, 2), 0, 0)
            }

            val subTv = TextView(activity).apply {
                textSize = 12f
                setTextColor(subTextColor)
                isSingleLine = true
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val statusBadge = TextView(activity).apply {
                textSize = 10f
                setTypeface(null, Typeface.BOLD)
                setPadding(dpToPx(activity, 8), dpToPx(activity, 2), dpToPx(activity, 8), dpToPx(activity, 2))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = dpToPx(activity, 6)
                }
            }

            subRow.addView(subTv)
            subRow.addView(statusBadge)
            infoCol.addView(titleTv)
            infoCol.addView(subRow)

            val btnAction = ImageButton(activity).apply {
                setBackgroundResource(getSelectableRes(activity))
                layoutParams = LinearLayout.LayoutParams(dpToPx(activity, 34), dpToPx(activity, 34))
            }

            val btnOptions = ImageButton(activity).apply {
                setImageResource(R.drawable.ic_more_vert)
                setColorFilter(subTextColor)
                setBackgroundResource(getSelectableRes(activity))
                layoutParams = LinearLayout.LayoutParams(dpToPx(activity, 34), dpToPx(activity, 34)).apply {
                    marginStart = dpToPx(activity, 2)
                }
            }

            topRow.addView(iconView)
            topRow.addView(infoCol)
            topRow.addView(btnAction)
            topRow.addView(btnOptions)

            val progressBar = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(activity, 4)).apply {
                    setMargins(0, dpToPx(activity, 8), 0, dpToPx(activity, 4))
                }
                isIndeterminate = false
                max = 100
            }

            val speedTv = TextView(activity).apply {
                textSize = 11f
                setTextColor(subTextColor)
            }

            card.addView(topRow)
            card.addView(progressBar)
            card.addView(speedTv)

            return DownloadViewHolder(card, iconView, titleTv, subTv, speedTv, progressBar, statusBadge, btnAction, btnOptions)
        }

        override fun onBindViewHolder(holder: DownloadViewHolder, position: Int) {
            val item = list[position]
            bindItem(holder, item, null)
        }

        override fun onBindViewHolder(holder: DownloadViewHolder, position: Int, payloads: MutableList<Any>) {
            if (payloads.isNotEmpty() && payloads[0] is DownloadProgressInfo) {
                bindItem(holder, list[position], payloads[0] as DownloadProgressInfo)
            } else {
                super.onBindViewHolder(holder, position, payloads)
            }
        }

        private fun bindItem(holder: DownloadViewHolder, item: DownloadItem, liveInfo: DownloadProgressInfo?) {
            holder.titleTv.text = item.fileName

            val fileTypeIcon = getFileTypeDrawable(item.fileName, item.mimeType)
            holder.iconView.setImageResource(fileTypeIcon)

            val currentStatus = liveInfo?.status ?: item.status
            val currentDownloaded = liveInfo?.downloadedBytes ?: 0L
            val currentTotal = if ((liveInfo?.totalBytes ?: 0L) > 0) liveInfo!!.totalBytes else item.fileSize
            val percent = liveInfo?.progressPercent ?: 0
            val speed = liveInfo?.speedBytesPerSec ?: 0L
            val eta = liveInfo?.etaSeconds ?: 0L

            when (currentStatus) {
                HazeDownloadEngine.STATUS_DOWNLOADING, "ACTIVE" -> {
                    holder.statusBadge.text = "DOWNLOADING"
                    holder.statusBadge.setTextColor(accentColor)
                    holder.statusBadge.background = GradientDrawable().apply {
                        cornerRadius = dpToPx(activity, 8).toFloat()
                        setColor(if (isDark) Color.parseColor("#1A2B4C") else Color.parseColor("#E8F0FE"))
                    }

                    holder.progressBar.visibility = View.VISIBLE
                    holder.progressBar.progress = percent
                    holder.speedTv.visibility = View.VISIBLE

                    val speedStr = if (speed > 0) "${formatFileSize(speed)}/s" else "Connecting..."
                    val etaStr = if (eta > 0) " • ${formatEta(eta)}" else ""
                    val downloadedStr = "${formatFileSize(currentDownloaded)} / ${formatFileSize(currentTotal)}"
                    holder.subTv.text = downloadedStr
                    holder.speedTv.text = "$speedStr$etaStr • $percent%"

                    holder.btnAction.visibility = View.VISIBLE
                    holder.btnAction.setImageResource(R.drawable.ic_pause)
                    holder.btnAction.setColorFilter(accentColor)
                    holder.btnAction.setOnClickListener { onPauseClick(item) }
                }
                HazeDownloadEngine.STATUS_QUEUED -> {
                    holder.statusBadge.text = "QUEUED"
                    holder.statusBadge.setTextColor(Color.parseColor("#FB8C00"))
                    holder.statusBadge.background = GradientDrawable().apply {
                        cornerRadius = dpToPx(activity, 8).toFloat()
                        setColor(if (isDark) Color.parseColor("#3E2723") else Color.parseColor("#FFF3E0"))
                    }

                    holder.progressBar.visibility = View.GONE
                    holder.speedTv.visibility = View.GONE
                    holder.subTv.text = "Waiting for available slot • ${formatFileSize(item.fileSize)}"

                    holder.btnAction.visibility = View.VISIBLE
                    holder.btnAction.setImageResource(R.drawable.ic_close)
                    holder.btnAction.setColorFilter(subTextColor)
                    holder.btnAction.setOnClickListener { onCancelClick(item) }
                }
                HazeDownloadEngine.STATUS_PAUSED -> {
                    holder.statusBadge.text = "PAUSED"
                    holder.statusBadge.setTextColor(subTextColor)
                    holder.statusBadge.background = GradientDrawable().apply {
                        cornerRadius = dpToPx(activity, 8).toFloat()
                        setColor(if (isDark) Color.parseColor("#262626") else Color.parseColor("#EEEEEE"))
                    }

                    holder.progressBar.visibility = View.VISIBLE
                    holder.progressBar.progress = percent
                    holder.speedTv.visibility = View.GONE
                    holder.subTv.text = "Paused • ${formatFileSize(currentDownloaded)} / ${formatFileSize(item.fileSize)}"

                    holder.btnAction.visibility = View.VISIBLE
                    holder.btnAction.setImageResource(R.drawable.ic_play)
                    holder.btnAction.setColorFilter(accentColor)
                    holder.btnAction.setOnClickListener { onResumeClick(item) }
                }
                HazeDownloadEngine.STATUS_COMPLETED -> {
                    holder.statusBadge.text = "DONE"
                    holder.statusBadge.setTextColor(Color.parseColor("#2E7D32"))
                    holder.statusBadge.background = GradientDrawable().apply {
                        cornerRadius = dpToPx(activity, 8).toFloat()
                        setColor(if (isDark) Color.parseColor("#1B381E") else Color.parseColor("#E8F5E9"))
                    }

                    holder.progressBar.visibility = View.GONE
                    holder.speedTv.visibility = View.GONE
                    val dateStr = timeFormat.format(Date(item.timestamp))
                    holder.subTv.text = "${formatFileSize(item.fileSize)} • $dateStr"

                    holder.btnAction.visibility = View.GONE
                }
                HazeDownloadEngine.STATUS_FAILED, HazeDownloadEngine.STATUS_CANCELLED -> {
                    holder.statusBadge.text = if (currentStatus == HazeDownloadEngine.STATUS_FAILED) "FAILED" else "CANCELLED"
                    holder.statusBadge.setTextColor(Color.parseColor("#D32F2F"))
                    holder.statusBadge.background = GradientDrawable().apply {
                        cornerRadius = dpToPx(activity, 8).toFloat()
                        setColor(if (isDark) Color.parseColor("#3C1B1B") else Color.parseColor("#FFEBEE"))
                    }

                    holder.progressBar.visibility = View.GONE
                    holder.speedTv.visibility = View.GONE
                    holder.subTv.text = "Failed to download • Tap retry"

                    holder.btnAction.visibility = View.VISIBLE
                    holder.btnAction.setImageResource(R.drawable.ic_refresh)
                    holder.btnAction.setColorFilter(accentColor)
                    holder.btnAction.setOnClickListener { onRetryClick(item) }
                }
                else -> {
                    holder.statusBadge.text = currentStatus
                    holder.progressBar.visibility = View.GONE
                    holder.speedTv.visibility = View.GONE
                    holder.btnAction.visibility = View.GONE
                }
            }

            holder.itemView.setOnClickListener { onItemClick(item) }
            holder.btnOptions.setOnClickListener { onItemOptions(item) }
        }

        override fun getItemCount(): Int = list.size

        private fun getFileTypeDrawable(fileName: String, mimeType: String): Int {
            val lower = fileName.lowercase()
            return when {
                lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".webp") || lower.endsWith(".gif") || mimeType.startsWith("image/") -> R.drawable.ic_image
                lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".webm") || lower.endsWith(".avi") || mimeType.startsWith("video/") -> R.drawable.ic_extra
                lower.endsWith(".mp3") || lower.endsWith(".wav") || lower.endsWith(".flac") || lower.endsWith(".m4a") || mimeType.startsWith("audio/") -> R.drawable.ic_volume_up
                lower.endsWith(".pdf") || lower.endsWith(".doc") || lower.endsWith(".docx") || lower.endsWith(".txt") -> R.drawable.ic_file
                lower.endsWith(".zip") || lower.endsWith(".rar") || lower.endsWith(".7z") || lower.endsWith(".tar") || lower.endsWith(".gz") -> R.drawable.ic_folder
                lower.endsWith(".apk") -> R.drawable.ic_download
                lower.endsWith(".html") || lower.endsWith(".js") || lower.endsWith(".css") || lower.endsWith(".json") || lower.endsWith(".xml") -> R.drawable.ic_code
                else -> R.drawable.ic_download
            }
        }
    }

    private fun formatFileSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
        return String.format(Locale.getDefault(), "%.1f %s", bytes / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
    }

    private fun formatEta(seconds: Long): String {
        return when {
            seconds < 60 -> "${seconds}s remaining"
            seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s remaining"
            else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m remaining"
        }
    }

    private fun showDownloadSettingsDialog(
        activity: MainActivity,
        surfaceColor: Int,
        textColor: Int,
        subTextColor: Int,
        strokeColor: Int
    ) {
        val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(activity)
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(surfaceColor)
                val r = dpToPx(activity, 16).toFloat()
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            }
            setPadding(0, dpToPx(activity, 16), 0, dpToPx(activity, 16))
        }
        
        val title = TextView(activity).apply {
            text = "Download Settings"
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setTextColor(textColor)
            setPadding(dpToPx(activity, 20), 0, dpToPx(activity, 20), dpToPx(activity, 16))
        }
        root.addView(title)
        
        val div1 = View(activity).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
            setBackgroundColor(strokeColor)
        }
        root.addView(div1)
        
        val rowLocation = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(activity, 20), dpToPx(activity, 16), dpToPx(activity, 20), dpToPx(activity, 16))
            setBackgroundResource(getSelectableRes(activity))
            setOnClickListener {
                activity.triggerStrongHaptic()
                activity.downloadDirPickerLauncher.launch(null)
            }
        }
        val locTitle = TextView(activity).apply {
            text = "Download location"
            textSize = 16f
            setTextColor(textColor)
            setPadding(0, 0, 0, dpToPx(activity, 4))
        }
        
        val customUriStr = activity.getSharedPreferences("HazePrefs", Context.MODE_PRIVATE).getString("custom_download_dir_uri", null)
        val locSub = TextView(activity).apply {
            text = getDisplayPathFromUri(activity, customUriStr)
            textSize = 12f
            setTextColor(subTextColor)
        }

        rowLocation.setOnClickListener {
            activity.triggerStrongHaptic()
            activity.onCustomDirSelected = { uri ->
                locSub.text = getDisplayPathFromUri(activity, uri.toString())
            }
            activity.downloadDirPickerLauncher.launch(null)
        }

        rowLocation.addView(locTitle)
        rowLocation.addView(locSub)
        root.addView(rowLocation)
        
        val div2 = View(activity).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
            setBackgroundColor(strokeColor)
        }
        root.addView(div2)
        
        val rowPrompt = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpToPx(activity, 20), dpToPx(activity, 16), dpToPx(activity, 20), dpToPx(activity, 16))
            setBackgroundResource(getSelectableRes(activity))
        }
        val promptCol = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val promptTitle = TextView(activity).apply {
            text = "Ask where to save files"
            textSize = 16f
            setTextColor(textColor)
            setPadding(0, 0, 0, dpToPx(activity, 4))
        }
        val promptSub = TextView(activity).apply {
            text = "Before downloading"
            textSize = 12f
            setTextColor(subTextColor)
        }
        promptCol.addView(promptTitle)
        promptCol.addView(promptSub)
        
        val prefs = activity.getSharedPreferences("HazePrefs", Context.MODE_PRIVATE)
        val swPrompt = Switch(activity).apply {
            isChecked = prefs.getBoolean("download_ask_location", false)
        }
        rowPrompt.addView(promptCol)
        rowPrompt.addView(swPrompt)
        root.addView(rowPrompt)
        
        rowPrompt.setOnClickListener {
            activity.triggerStrongHaptic()
            val newState = !swPrompt.isChecked
            swPrompt.isChecked = newState
            prefs.edit().putBoolean("download_ask_location", newState).apply()
        }
        
        dialog.setContentView(root)
        dialog.show()
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
