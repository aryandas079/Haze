package com.example.haze

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

object HazeBookmarksManager {

    fun showBookmarksDialog(activity: MainActivity, onNavigate: (String) -> Unit) {
        val isDark = activity.getSharedPreferences("HazePrefs", Context.MODE_PRIVATE).getBoolean("is_dark", false)
        val accentColor = Color.parseColor(activity.getSharedPreferences("HazePrefs", Context.MODE_PRIVATE).getString("accent_color", "#0A56D0"))
        val bgColor = if (isDark) Color.parseColor("#181818") else Color.parseColor("#FAFAFA")
        val cardBgColor = if (isDark) Color.parseColor("#222222") else Color.parseColor("#FFFFFF")
        val textColor = if (isDark) Color.WHITE else Color.parseColor("#202124")
        val subTextColor = if (isDark) Color.parseColor("#9AA0A6") else Color.parseColor("#5F6368")
        val strokeColor = if (isDark) Color.parseColor("#333333") else Color.parseColor("#E0E0E0")

        val dialog = Dialog(activity, android.R.style.Theme_Light_NoTitleBar)
        dialog.window?.statusBarColor = bgColor

        // Current navigation state
        var currentFolderId = 0L
        val folderHistory = mutableListOf<BookmarkFolder>()

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgColor)
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        // Top Header
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
        }
        header.addView(btnBack)

        val tvTitle = TextView(activity).apply {
            id = View.generateViewId()
            text = "Bookmarks"
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

        val actionButtons = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = RelativeLayout.LayoutParams(RelativeLayout.LayoutParams.WRAP_CONTENT, RelativeLayout.LayoutParams.WRAP_CONTENT).apply {
                addRule(RelativeLayout.ALIGN_PARENT_END)
                addRule(RelativeLayout.CENTER_VERTICAL)
            }
        }

        val btnNewFolder = ImageButton(activity).apply {
            setImageResource(R.drawable.ic_folder)
            setColorFilter(accentColor)
            setBackgroundResource(getSelectableRes(activity))
            setPadding(dpToPx(activity, 8), dpToPx(activity, 8), dpToPx(activity, 8), dpToPx(activity, 8))
            layoutParams = LinearLayout.LayoutParams(dpToPx(activity, 38), dpToPx(activity, 38))
        }

        val btnAddBookmark = ImageButton(activity).apply {
            setImageResource(R.drawable.ic_add)
            setColorFilter(accentColor)
            setBackgroundResource(getSelectableRes(activity))
            setPadding(dpToPx(activity, 8), dpToPx(activity, 8), dpToPx(activity, 8), dpToPx(activity, 8))
            layoutParams = LinearLayout.LayoutParams(dpToPx(activity, 38), dpToPx(activity, 38)).apply {
                marginStart = dpToPx(activity, 4)
            }
        }

        actionButtons.addView(btnNewFolder)
        actionButtons.addView(btnAddBookmark)
        header.addView(actionButtons)
        root.addView(header)

        // Breadcrumbs Row
        val breadcrumbsContainer = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpToPx(activity, 20), dpToPx(activity, 4), dpToPx(activity, 20), dpToPx(activity, 10))
        }
        val tvBreadcrumb = TextView(activity).apply {
            text = "📁 Mobile Bookmarks"
            textSize = 13f
            setTypeface(null, Typeface.BOLD)
            setTextColor(subTextColor)
        }
        breadcrumbsContainer.addView(tvBreadcrumb)
        root.addView(breadcrumbsContainer)

        // Content Area
        val contentFrame = FrameLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }

        val emptyView = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)

            val emptyIcon = ImageView(activity).apply {
                setImageResource(R.drawable.ic_star)
                setColorFilter(subTextColor)
                layoutParams = LinearLayout.LayoutParams(dpToPx(activity, 64), dpToPx(activity, 64))
            }
            val emptyText = TextView(activity).apply {
                text = "No bookmarks in this folder"
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
        root.addView(contentFrame)

        fun refreshList() {
            val db = HazeDatabase.getInstance(activity)
            val subfolders = db.getSubFolders(currentFolderId)
            val bookmarks = db.getBookmarks(currentFolderId)

            if (folderHistory.isEmpty()) {
                tvBreadcrumb.text = "📁 Mobile Bookmarks"
            } else {
                val path = StringBuilder("📁 Root")
                for (f in folderHistory) {
                    path.append(" > ").append(f.name)
                }
                tvBreadcrumb.text = path.toString()
            }

            if (subfolders.isEmpty() && bookmarks.isEmpty()) {
                emptyView.visibility = View.VISIBLE
                recyclerView.visibility = View.GONE
            } else {
                emptyView.visibility = View.GONE
                recyclerView.visibility = View.VISIBLE
                recyclerView.adapter = BookmarksAdapter(
                    activity,
                    subfolders,
                    bookmarks,
                    onFolderClick = { folder ->
                        activity.triggerStrongHaptic()
                        folderHistory.add(folder)
                        currentFolderId = folder.id
                        refreshList()
                    },
                    onBookmarkClick = { bookmark ->
                        activity.triggerStrongHaptic()
                        dialog.dismiss()
                        onNavigate(bookmark.url)
                    },
                    onFolderOptions = { folder ->
                        showFolderOptions(activity, folder) { refreshList() }
                    },
                    onBookmarkOptions = { bookmark ->
                        showBookmarkOptions(activity, bookmark, onNavigate, { refreshList() })
                    },
                    isDark, textColor, subTextColor, accentColor
                )
            }
        }

        btnBack.setOnClickListener {
            activity.triggerStrongHaptic(it)
            if (folderHistory.isNotEmpty()) {
                folderHistory.removeAt(folderHistory.size - 1)
                currentFolderId = if (folderHistory.isNotEmpty()) folderHistory.last().id else 0L
                refreshList()
            } else {
                dialog.dismiss()
            }
        }

        btnNewFolder.setOnClickListener {
            activity.triggerStrongHaptic(it)
            showCreateFolderDialog(activity, currentFolderId) { refreshList() }
        }

        btnAddBookmark.setOnClickListener {
            activity.triggerStrongHaptic(it)
            showAddBookmarkDialog(activity, currentFolderId) { refreshList() }
        }

        refreshList()
        dialog.setContentView(root)
        dialog.show()
    }

    private fun showCreateFolderDialog(activity: MainActivity, parentId: Long, onCreated: () -> Unit) {
        val input = EditText(activity).apply {
            hint = "Folder name (e.g. Work, Tech)"
            setSingleLine()
        }
        val container = FrameLayout(activity).apply {
            setPadding(dpToPx(activity, 24), dpToPx(activity, 16), dpToPx(activity, 24), dpToPx(activity, 8))
            addView(input)
        }

        AlertDialog.Builder(activity)
            .setTitle("New Folder")
            .setView(container)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    HazeDatabase.getInstance(activity).createFolder(name, parentId)
                    Toast.makeText(activity, "Folder '$name' created", Toast.LENGTH_SHORT).show()
                    onCreated()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showAddBookmarkDialog(activity: MainActivity, folderId: Long, onAdded: () -> Unit) {
        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(activity, 24), dpToPx(activity, 16), dpToPx(activity, 24), dpToPx(activity, 8))
        }

        val titleInput = EditText(activity).apply { hint = "Title"; setSingleLine() }
        val urlInput = EditText(activity).apply { hint = "URL (https://...)"; setSingleLine() }
        layout.addView(titleInput)
        layout.addView(urlInput)

        AlertDialog.Builder(activity)
            .setTitle("Add Bookmark")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val title = titleInput.text.toString().trim()
                var url = urlInput.text.toString().trim()
                if (title.isNotEmpty() && url.isNotEmpty()) {
                    if (!url.startsWith("http://") && !url.startsWith("https://")) url = "https://$url"
                    HazeDatabase.getInstance(activity).addBookmark(title, url, folderId)
                    Toast.makeText(activity, "Bookmark saved", Toast.LENGTH_SHORT).show()
                    onAdded()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    fun showBookmarkThisPageDialog(activity: MainActivity, title: String, url: String, onDone: (() -> Unit)? = null) {
        val db = HazeDatabase.getInstance(activity)
        val folders = db.getAllFolders()

        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpToPx(activity, 24), dpToPx(activity, 16), dpToPx(activity, 24), dpToPx(activity, 8))
        }

        val titleInput = EditText(activity).apply {
            setText(title.ifEmpty { "Web Page" })
            setSingleLine()
        }
        val urlInput = EditText(activity).apply {
            setText(url)
            setSingleLine()
        }

        val folderSpinner = Spinner(activity)
        val folderNames = mutableListOf("Mobile Bookmarks (Root)")
        val folderIds = mutableListOf(0L)
        for (f in folders) {
            if (f.id != 0L) {
                folderNames.add(f.name)
                folderIds.add(f.id)
            }
        }
        folderSpinner.adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, folderNames)

        layout.addView(TextView(activity).apply { text = "Name"; textSize = 12f; setTextColor(Color.GRAY) })
        layout.addView(titleInput)
        layout.addView(TextView(activity).apply { text = "URL"; textSize = 12f; setTextColor(Color.GRAY) })
        layout.addView(urlInput)
        layout.addView(TextView(activity).apply { text = "Save to Folder"; textSize = 12f; setTextColor(Color.GRAY) })
        layout.addView(folderSpinner)

        AlertDialog.Builder(activity)
            .setTitle("Bookmark Page")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                activity.triggerStrongHaptic()
                val selectedFolderId = folderIds[folderSpinner.selectedItemPosition]
                val t = titleInput.text.toString().trim()
                val u = urlInput.text.toString().trim()
                db.addBookmark(t, u, selectedFolderId)
                Toast.makeText(activity, "Bookmarked!", Toast.LENGTH_SHORT).show()
                onDone?.invoke()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showFolderOptions(activity: MainActivity, folder: BookmarkFolder, onUpdated: () -> Unit) {
        val options = arrayOf("Rename", "Delete folder")
        AlertDialog.Builder(activity)
            .setTitle(folder.name)
            .setItems(options) { _, which ->
                activity.triggerStrongHaptic()
                when (which) {
                    0 -> { // Rename
                        val input = EditText(activity).apply { setText(folder.name); setSingleLine() }
                        val frame = FrameLayout(activity).apply {
                            setPadding(dpToPx(activity, 24), dpToPx(activity, 16), dpToPx(activity, 24), dpToPx(activity, 8))
                            addView(input)
                        }
                        AlertDialog.Builder(activity)
                            .setTitle("Rename Folder")
                            .setView(frame)
                            .setPositiveButton("Save") { _, _ ->
                                val name = input.text.toString().trim()
                                if (name.isNotEmpty()) {
                                    HazeDatabase.getInstance(activity).renameFolder(folder.id, name)
                                    onUpdated()
                                }
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                    1 -> { // Delete
                        AlertDialog.Builder(activity)
                            .setTitle("Delete '${folder.name}'?")
                            .setMessage("All bookmarks inside this folder will also be removed.")
                            .setPositiveButton("Delete") { _, _ ->
                                HazeDatabase.getInstance(activity).deleteFolder(folder.id)
                                Toast.makeText(activity, "Folder deleted", Toast.LENGTH_SHORT).show()
                                onUpdated()
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showBookmarkOptions(activity: MainActivity, bookmark: BookmarkItem, onNavigate: (String) -> Unit, onUpdated: () -> Unit) {
        val options = arrayOf("Open in new tab", "Edit", "Move to folder", "Share", "Delete")
        AlertDialog.Builder(activity)
            .setTitle(bookmark.title)
            .setItems(options) { _, which ->
                activity.triggerStrongHaptic()
                when (which) {
                    0 -> {
                        activity.addNewTab(bookmark.url)
                        Toast.makeText(activity, "Opened in new tab", Toast.LENGTH_SHORT).show()
                    }
                    1 -> { // Edit
                        val layout = LinearLayout(activity).apply {
                            orientation = LinearLayout.VERTICAL
                            setPadding(dpToPx(activity, 24), dpToPx(activity, 16), dpToPx(activity, 24), dpToPx(activity, 8))
                        }
                        val titleInput = EditText(activity).apply { setText(bookmark.title); setSingleLine() }
                        val urlInput = EditText(activity).apply { setText(bookmark.url); setSingleLine() }
                        layout.addView(titleInput)
                        layout.addView(urlInput)

                        AlertDialog.Builder(activity)
                            .setTitle("Edit Bookmark")
                            .setView(layout)
                            .setPositiveButton("Save") { _, _ ->
                                val t = titleInput.text.toString().trim()
                                val u = urlInput.text.toString().trim()
                                if (t.isNotEmpty() && u.isNotEmpty()) {
                                    HazeDatabase.getInstance(activity).updateBookmark(bookmark.id, t, u, bookmark.folderId)
                                    onUpdated()
                                }
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                    2 -> { // Move
                        val allFolders = HazeDatabase.getInstance(activity).getAllFolders()
                        val folderNames = mutableListOf("Mobile Bookmarks (Root)")
                        val folderIds = mutableListOf(0L)
                        for (f in allFolders) {
                            if (f.id != 0L) {
                                folderNames.add(f.name)
                                folderIds.add(f.id)
                            }
                        }
                        AlertDialog.Builder(activity)
                            .setTitle("Move to Folder")
                            .setItems(folderNames.toTypedArray()) { _, fIdx ->
                                val targetFolderId = folderIds[fIdx]
                                HazeDatabase.getInstance(activity).updateBookmark(bookmark.id, bookmark.title, bookmark.url, targetFolderId)
                                Toast.makeText(activity, "Moved to ${folderNames[fIdx]}", Toast.LENGTH_SHORT).show()
                                onUpdated()
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                    3 -> { // Share
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, bookmark.title)
                            putExtra(Intent.EXTRA_TEXT, bookmark.url)
                        }
                        activity.startActivity(Intent.createChooser(intent, "Share bookmark"))
                    }
                    4 -> { // Delete
                        HazeDatabase.getInstance(activity).deleteBookmark(bookmark.id)
                        Toast.makeText(activity, "Bookmark deleted", Toast.LENGTH_SHORT).show()
                        onUpdated()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private class BookmarksAdapter(
        private val activity: MainActivity,
        private val folders: List<BookmarkFolder>,
        private val bookmarks: List<BookmarkItem>,
        private val onFolderClick: (BookmarkFolder) -> Unit,
        private val onBookmarkClick: (BookmarkItem) -> Unit,
        private val onFolderOptions: (BookmarkFolder) -> Unit,
        private val onBookmarkOptions: (BookmarkItem) -> Unit,
        private val isDark: Boolean,
        private val textColor: Int,
        private val subTextColor: Int,
        private val accentColor: Int
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        override fun getItemViewType(position: Int): Int {
            return if (position < folders.size) 0 else 1
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(activity, 58))
                setPadding(dpToPx(activity, 20), 0, dpToPx(activity, 14), 0)
                setBackgroundResource(getSelectableRes(activity))
            }

            val icon = ImageView(activity).apply {
                layoutParams = LinearLayout.LayoutParams(dpToPx(activity, 24), dpToPx(activity, 24)).apply {
                    marginEnd = dpToPx(activity, 16)
                }
            }

            val textCol = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }

            val titleTv = TextView(activity).apply {
                textSize = 15f
                setTextColor(textColor)
                isSingleLine = true
            }

            val subTv = TextView(activity).apply {
                textSize = 12f
                setTextColor(subTextColor)
                isSingleLine = true
            }

            textCol.addView(titleTv)
            textCol.addView(subTv)

            val btnMore = ImageButton(activity).apply {
                setImageResource(R.drawable.ic_more_vert)
                setColorFilter(subTextColor)
                setBackgroundResource(getSelectableRes(activity))
                layoutParams = LinearLayout.LayoutParams(dpToPx(activity, 32), dpToPx(activity, 32))
            }

            row.addView(icon)
            row.addView(textCol)
            row.addView(btnMore)

            return BookmarkRowHolder(row, icon, titleTv, subTv, btnMore)
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val h = holder as BookmarkRowHolder
            if (position < folders.size) {
                val f = folders[position]
                h.icon.setImageResource(R.drawable.ic_folder)
                h.icon.setColorFilter(accentColor)
                h.titleTv.text = f.name
                val count = HazeDatabase.getInstance(activity).getBookmarks(f.id).size
                h.subTv.text = "$count items"
                h.subTv.visibility = View.VISIBLE

                h.itemView.setOnClickListener { onFolderClick(f) }
                h.itemView.setOnLongClickListener { onFolderOptions(f); true }
                h.btnMore.setOnClickListener { onFolderOptions(f) }
            } else {
                val b = bookmarks[position - folders.size]
                h.icon.setImageResource(R.drawable.ic_star_filled)
                h.icon.setColorFilter(Color.parseColor("#FBBC05"))
                h.titleTv.text = b.title
                val domain = try { Uri.parse(b.url).host ?: b.url } catch (e: Exception) { b.url }
                h.subTv.text = domain
                h.subTv.visibility = View.VISIBLE

                h.itemView.setOnClickListener { onBookmarkClick(b) }
                h.itemView.setOnLongClickListener { onBookmarkOptions(b); true }
                h.btnMore.setOnClickListener { onBookmarkOptions(b) }
            }
        }

        override fun getItemCount(): Int = folders.size + bookmarks.size

        class BookmarkRowHolder(
            val view: View,
            val icon: ImageView,
            val titleTv: TextView,
            val subTv: TextView,
            val btnMore: ImageButton
        ) : RecyclerView.ViewHolder(view)
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
