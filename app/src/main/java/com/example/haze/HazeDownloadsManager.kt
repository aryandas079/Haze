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
import android.widget.*
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

object HazeDownloadsManager {

    fun initDownload(
        activity: MainActivity,
        url: String,
        userAgent: String,
        contentDisposition: String,
        mimeType: String,
        contentLength: Long,
        isPrivate: Boolean = false
    ) {
        val guessedFileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
        val sizeFormatted = formatFileSize(contentLength)
        
        val message = if (isPrivate) {
            "File: $guessedFileName\nSize: $sizeFormatted\nFrom: ${Uri.parse(url).host}\n\nWARNING: Downloaded files will be saved to your device and may remain after closing Private Mode."
        } else {
            "File: $guessedFileName\nSize: $sizeFormatted\nFrom: ${Uri.parse(url).host}"
        }

        AlertDialog.Builder(activity)
            .setTitle("Download File?")
            .setMessage(message)
            .setPositiveButton("Download") { _, _ ->
                activity.triggerStrongHaptic()
                HazeDownloadEngine.enqueueDownload(
                    context = activity,
                    url = url,
                    userAgent = userAgent,
                    contentDisposition = contentDisposition,
                    mimeType = mimeType,
                    contentLength = contentLength,
                    suggestedFileName = guessedFileName,
                    isPrivate = isPrivate
                )
                Toast.makeText(activity, "Download added: $guessedFileName", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun showDownloadsDialog(activity: MainActivity) {
        val isDark = activity.getSharedPreferences("HazePrefs", Context.MODE_PRIVATE).getBoolean("is_dark", false) || activity.isPrivateMode
        val accentColor = Color.parseColor(activity.getSharedPreferences("HazePrefs", Context.MODE_PRIVATE).getString("accent_color", "#0A56D0"))
        val bgColor = if (isDark) Color.parseColor("#121212") else Color.parseColor("#F8F9FA")
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
                addRule(RelativeLayout.ALIGN_PARENT_START)
                addRule(RelativeLayout.CENTER_VERTICAL)
            }
            setOnClickListener {
                activity.triggerStrongHaptic(it)
                dialog.dismiss()
            }
        }
        header.addView(btnBack)

        val tvTitle = TextView(activity).apply {
            text = "Downloads"
            textSize = 20f
            setTypeface(null, Typeface.BOLD)
            setTextColor(textColor)
            layoutParams = RelativeLayout.LayoutParams(RelativeLayout.LayoutParams.WRAP_CONTENT, RelativeLayout.LayoutParams.WRAP_CONTENT).apply {
                addRule(RelativeLayout.RIGHT_OF, btnBack.id)
                addRule(RelativeLayout.CENTER_VERTICAL)
                marginStart = dpToPx(activity, 16)
            }
        }
        header.addView(tvTitle)
        root.addView(header)

        // --- Category Tabs ---
        val tabsScroll = HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(dpToPx(activity, 16), 0, dpToPx(activity, 16), dpToPx(activity, 12))
            }
        }

        val tabsRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val categories = listOf("ALL", "ACTIVE", "COMPLETED", "FAILED")
        val tabButtons = mutableListOf<TextView>()

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

            val emptyIcon = ImageView(activity).apply {
                setImageResource(R.drawable.ic_download)
                setColorFilter(subTextColor)
                layoutParams = LinearLayout.LayoutParams(dpToPx(activity, 64), dpToPx(activity, 64)).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                }
            }
            val emptyTitle = TextView(activity).apply {
                text = "No downloads"
                textSize = 18f
                setTypeface(null, Typeface.BOLD)
                setTextColor(textColor)
                gravity = Gravity.CENTER
                textAlignment = View.TEXT_ALIGNMENT_CENTER
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    topMargin = dpToPx(activity, 16)
                    bottomMargin = dpToPx(activity, 6)
                }
            }
            val emptySub = TextView(activity).apply {
                text = "Files downloaded from the web will appear here."
                textSize = 14f
                setTextColor(subTextColor)
                gravity = Gravity.CENTER
                textAlignment = View.TEXT_ALIGNMENT_CENTER
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                }
            }
            addView(emptyIcon)
            addView(emptyTitle)
            addView(emptySub)
        }
        contentFrame.addView(emptyView)

        val recyclerView = RecyclerView(activity).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            layoutManager = LinearLayoutManager(activity)
        }
        contentFrame.addView(recyclerView)

        tabsScroll.addView(tabsRow)
        root.addView(tabsScroll)
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

            // Update Tab Badges & Selection
            for (i in categories.indices) {
                val btn = tabButtons[i]
                val cat = categories[i]
                val count = db.getAllDownloads(cat).size
                val label = when (cat) {
                    "ALL" -> "All ($count)"
                    "ACTIVE" -> "Active ($count)"
                    "COMPLETED" -> "Completed ($count)"
                    "FAILED" -> "Failed ($count)"
                    else -> cat
                }
                btn.text = label

                if (cat == activeFilter) {
                    btn.background = GradientDrawable().apply {
                        cornerRadius = dpToPx(activity, 20).toFloat()
                        setColor(accentColor)
                    }
                    btn.setTextColor(Color.WHITE)
                } else {
                    btn.background = GradientDrawable().apply {
                        cornerRadius = dpToPx(activity, 20).toFloat()
                        setColor(surfaceColor)
                        setStroke(dpToPx(activity, 1), strokeColor)
                    }
                    btn.setTextColor(subTextColor)
                }
            }
        }

        // Build Tabs
        for (cat in categories) {
            val tv = TextView(activity).apply {
                text = cat
                textSize = 12f
                setTypeface(null, Typeface.BOLD)
                setPadding(dpToPx(activity, 14), dpToPx(activity, 8), dpToPx(activity, 14), dpToPx(activity, 8))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = dpToPx(activity, 8)
                }
                setOnClickListener {
                    activity.triggerStrongHaptic(it)
                    activeFilter = cat
                    refreshList()
                }
            }
            tabButtons.add(tv)
            tabsRow.addView(tv)
        }

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
            val uri = FileProvider.getUriForFile(activity, "com.example.haze.fileprovider", file)
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
                            val uri = FileProvider.getUriForFile(activity, "com.example.haze.fileprovider", file)
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

    private fun dpToPx(context: Context, dp: Int): Int {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp.toFloat(), context.resources.displayMetrics).toInt()
    }

    private fun getSelectableRes(context: Context): Int {
        val out = TypedValue()
        context.theme.resolveAttribute(android.R.attr.selectableItemBackground, out, true)
        return out.resourceId
    }
}
