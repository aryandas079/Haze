package com.example.haze

import android.app.AlertDialog
import android.app.Dialog
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
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
        contentLength: Long
    ) {
        val guessedFileName = URLUtil.guessFileName(url, contentDisposition, mimeType)
        val sizeFormatted = formatFileSize(contentLength)

        AlertDialog.Builder(activity)
            .setTitle("Download File?")
            .setMessage("File: $guessedFileName\nSize: $sizeFormatted\nURL: ${Uri.parse(url).host}")
            .setPositiveButton("Download") { _, _ ->
                activity.triggerStrongHaptic()
                startDownload(activity, url, userAgent, guessedFileName, mimeType, contentLength)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startDownload(
        activity: MainActivity,
        url: String,
        userAgent: String,
        fileName: String,
        mimeType: String,
        contentLength: Long
    ) {
        try {
            val downloadManager = activity.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
            if (downloadManager == null) {
                Toast.makeText(activity, "Download service unavailable", Toast.LENGTH_SHORT).show()
                return
            }

            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setMimeType(mimeType)
                addRequestHeader("User-Agent", userAgent)
                setDescription("Downloading $fileName")
                setTitle(fileName)
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            }

            val downloadId = downloadManager.enqueue(request)
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val destFile = File(downloadsDir, fileName)

            HazeDatabase.getInstance(activity).addDownload(
                downloadId = downloadId,
                fileName = fileName,
                filePath = destFile.absolutePath,
                fileSize = contentLength,
                url = url,
                mimeType = mimeType,
                status = "ACTIVE"
            )

            Toast.makeText(activity, "Downloading $fileName...", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(activity, "Download failed to start: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    fun showDownloadsDialog(activity: MainActivity) {
        val isDark = activity.getSharedPreferences("HazePrefs", Context.MODE_PRIVATE).getBoolean("is_dark", false)
        val accentColor = Color.parseColor(activity.getSharedPreferences("HazePrefs", Context.MODE_PRIVATE).getString("accent_color", "#0A56D0"))
        val bgColor = if (isDark) Color.parseColor("#181818") else Color.parseColor("#FAFAFA")
        val textColor = if (isDark) Color.WHITE else Color.parseColor("#202124")
        val subTextColor = if (isDark) Color.parseColor("#9AA0A6") else Color.parseColor("#5F6368")

        val dialog = Dialog(activity, android.R.style.Theme_Light_NoTitleBar)
        dialog.window?.statusBarColor = bgColor

        var activeFilter = "ALL" // ALL, ACTIVE, COMPLETED, FAILED

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgColor)
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        // Header
        val header = RelativeLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(activity, 60))
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

        // Filter Tabs Row
        val tabsRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dpToPx(activity, 16), dpToPx(activity, 4), dpToPx(activity, 16), dpToPx(activity, 12))
        }

        val filterTabs = listOf("ALL", "ACTIVE", "COMPLETED", "FAILED")
        val tabButtons = mutableListOf<TextView>()

        val contentFrame = FrameLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }

        val emptyView = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)

            val emptyIcon = ImageView(activity).apply {
                setImageResource(R.drawable.ic_download)
                setColorFilter(subTextColor)
                layoutParams = LinearLayout.LayoutParams(dpToPx(activity, 64), dpToPx(activity, 64))
            }
            val emptyText = TextView(activity).apply {
                text = "No downloads"
                textSize = 15f
                setTextColor(subTextColor)
                setPadding(0, dpToPx(activity, 12), 0, 0)
            }
            addView(emptyIcon)
            addView(emptyText)
        }
        contentFrame.addView(emptyView)

        val recyclerView = RecyclerView(activity).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            layoutManager = LinearLayoutManager(activity)
        }
        contentFrame.addView(recyclerView)
        root.addView(tabsRow)
        root.addView(contentFrame)

        fun refreshList() {
            // Check status against system DownloadManager
            val dm = activity.getSystemService(Context.DOWNLOAD_SERVICE) as? DownloadManager
            val db = HazeDatabase.getInstance(activity)
            val currentList = db.getAllDownloads(activeFilter)

            // Sync active downloads
            if (dm != null) {
                for (item in currentList) {
                    if (item.status == "ACTIVE") {
                        val query = DownloadManager.Query().setFilterById(item.downloadId)
                        val cursor = dm.query(query)
                        if (cursor.moveToFirst()) {
                            val statusIdx = cursor.getColumnIndex(DownloadManager.COLUMN_STATUS)
                            if (statusIdx != -1) {
                                when (cursor.getInt(statusIdx)) {
                                    DownloadManager.STATUS_SUCCESSFUL -> {
                                        val localUriIdx = cursor.getColumnIndex(DownloadManager.COLUMN_LOCAL_URI)
                                        val path = if (localUriIdx != -1) cursor.getString(localUriIdx) else item.filePath
                                        db.updateDownloadStatus(item.downloadId, "COMPLETED", path)
                                    }
                                    DownloadManager.STATUS_FAILED -> {
                                        db.updateDownloadStatus(item.downloadId, "FAILED")
                                    }
                                }
                            }
                        }
                        cursor.close()
                    }
                }
            }

            val updatedList = db.getAllDownloads(activeFilter)
            if (updatedList.isEmpty()) {
                emptyView.visibility = View.VISIBLE
                recyclerView.visibility = View.GONE
            } else {
                emptyView.visibility = View.GONE
                recyclerView.visibility = View.VISIBLE
                recyclerView.adapter = DownloadsAdapter(activity, updatedList, { item ->
                    openDownloadedFile(activity, item)
                }, { item ->
                    showDownloadOptions(activity, item) { refreshList() }
                }, isDark, textColor, subTextColor, accentColor)
            }

            // Update Tab selection style
            for (i in filterTabs.indices) {
                val btn = tabButtons[i]
                if (filterTabs[i] == activeFilter) {
                    btn.setTextColor(Color.WHITE)
                    btn.setBackgroundColor(accentColor)
                } else {
                    btn.setTextColor(subTextColor)
                    btn.setBackgroundColor(Color.TRANSPARENT)
                }
            }
        }

        for (filter in filterTabs) {
            val tv = TextView(activity).apply {
                text = filter
                textSize = 12f
                setTypeface(null, Typeface.BOLD)
                setPadding(dpToPx(activity, 14), dpToPx(activity, 6), dpToPx(activity, 14), dpToPx(activity, 6))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = dpToPx(activity, 8)
                }
                setOnClickListener {
                    activity.triggerStrongHaptic(it)
                    activeFilter = filter
                    refreshList()
                }
            }
            tabButtons.add(tv)
            tabsRow.addView(tv)
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
            Toast.makeText(activity, "No application found to open this file", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showDownloadOptions(activity: MainActivity, item: DownloadItem, onUpdated: () -> Unit) {
        val options = arrayOf("Open file", "Share file", "Open file location", "Rename", "Delete")
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
                                type = item.mimeType
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            activity.startActivity(Intent.createChooser(intent, "Share file"))
                        } else {
                            Toast.makeText(activity, "File not found", Toast.LENGTH_SHORT).show()
                        }
                    }
                    2 -> { // Open Location
                        try {
                            val intent = Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)
                            activity.startActivity(intent)
                        } catch (e: Exception) {
                            Toast.makeText(activity, "Downloads location: ${item.filePath}", Toast.LENGTH_LONG).show()
                        }
                    }
                    3 -> { // Rename
                        val input = EditText(activity).apply { setText(item.fileName); setSingleLine() }
                        val frame = FrameLayout(activity).apply {
                            setPadding(dpToPx(activity, 24), dpToPx(activity, 16), dpToPx(activity, 24), dpToPx(activity, 8))
                            addView(input)
                        }
                        AlertDialog.Builder(activity)
                            .setTitle("Rename File")
                            .setView(frame)
                            .setPositiveButton("Save") { _, _ ->
                                val newName = input.text.toString().trim()
                                if (newName.isNotEmpty()) {
                                    HazeDatabase.getInstance(activity).renameDownload(item.id, newName)
                                    onUpdated()
                                }
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                    4 -> { // Delete
                        val file = File(item.filePath)
                        if (file.exists()) file.delete()
                        HazeDatabase.getInstance(activity).deleteDownload(item.id)
                        Toast.makeText(activity, "Download deleted", Toast.LENGTH_SHORT).show()
                        onUpdated()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private class DownloadsAdapter(
        private val activity: MainActivity,
        private val list: List<DownloadItem>,
        private val onItemClick: (DownloadItem) -> Unit,
        private val onItemOptions: (DownloadItem) -> Unit,
        private val isDark: Boolean,
        private val textColor: Int,
        private val subTextColor: Int,
        private val accentColor: Int
    ) : RecyclerView.Adapter<DownloadsAdapter.DownloadHolder>() {

        private val timeFormat = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())

        class DownloadHolder(
            val view: View,
            val icon: ImageView,
            val titleTv: TextView,
            val subTv: TextView,
            val statusBadge: TextView,
            val btnMore: ImageButton
        ) : RecyclerView.ViewHolder(view)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DownloadHolder {
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(activity, 64))
                setPadding(dpToPx(activity, 16), 0, dpToPx(activity, 12), 0)
                setBackgroundResource(getSelectableRes(activity))
            }

            val icon = ImageView(activity).apply {
                layoutParams = LinearLayout.LayoutParams(dpToPx(activity, 26), dpToPx(activity, 26)).apply {
                    marginEnd = dpToPx(activity, 14)
                }
            }

            val textCol = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val titleTv = TextView(activity).apply {
                textSize = 14f
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
                setPadding(dpToPx(activity, 6), dpToPx(activity, 2), dpToPx(activity, 6), dpToPx(activity, 2))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                    marginEnd = dpToPx(activity, 8)
                }
            }

            subRow.addView(subTv)
            subRow.addView(statusBadge)
            textCol.addView(titleTv)
            textCol.addView(subRow)

            val btnMore = ImageButton(activity).apply {
                setImageResource(R.drawable.ic_more_vert)
                setColorFilter(subTextColor)
                setBackgroundResource(getSelectableRes(activity))
                layoutParams = LinearLayout.LayoutParams(dpToPx(activity, 32), dpToPx(activity, 32))
            }

            row.addView(icon)
            row.addView(textCol)
            row.addView(btnMore)

            return DownloadHolder(row, icon, titleTv, subTv, statusBadge, btnMore)
        }

        override fun onBindViewHolder(holder: DownloadHolder, position: Int) {
            val item = list[position]
            holder.titleTv.text = item.fileName

            val sizeStr = if (item.fileSize > 0) formatFileSize(item.fileSize) else ""
            val dateStr = timeFormat.format(Date(item.timestamp))
            holder.subTv.text = if (sizeStr.isNotEmpty()) "$sizeStr • $dateStr" else dateStr

            when (item.status) {
                "COMPLETED" -> {
                    holder.statusBadge.text = "DONE"
                    holder.statusBadge.setTextColor(Color.parseColor("#34A853"))
                    holder.icon.setImageResource(R.drawable.ic_download)
                    holder.icon.setColorFilter(Color.parseColor("#34A853"))
                }
                "ACTIVE" -> {
                    holder.statusBadge.text = "DOWNLOADING"
                    holder.statusBadge.setTextColor(accentColor)
                    holder.icon.setImageResource(R.drawable.ic_download)
                    holder.icon.setColorFilter(accentColor)
                }
                "FAILED" -> {
                    holder.statusBadge.text = "FAILED"
                    holder.statusBadge.setTextColor(Color.parseColor("#EA4335"))
                    holder.icon.setImageResource(R.drawable.ic_close)
                    holder.icon.setColorFilter(Color.parseColor("#EA4335"))
                }
                else -> {
                    holder.statusBadge.text = item.status
                    holder.statusBadge.setTextColor(subTextColor)
                }
            }

            holder.itemView.setOnClickListener { onItemClick(item) }
            holder.itemView.setOnLongClickListener { onItemOptions(item); true }
            holder.btnMore.setOnClickListener { onItemOptions(item) }
        }

        override fun getItemCount(): Int = list.size
    }

    private fun formatFileSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
        return String.format(Locale.getDefault(), "%.1f %s", bytes / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
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
