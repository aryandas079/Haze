package com.example.haze

import android.app.AlertDialog
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.*

object HazeBookmarksManager {

    fun showBookmarksDialog(activity: MainActivity, onNavigate: (String) -> Unit) {
        val isDark = activity.getSharedPreferences("HazePrefs", Context.MODE_PRIVATE).getBoolean("is_dark", false) || activity.isPrivateMode
        val accentColor = Color.parseColor(activity.getSharedPreferences("HazePrefs", Context.MODE_PRIVATE).getString("accent_color", "#0A56D0"))
        val bgColor = if (isDark) Color.parseColor("#121212") else Color.parseColor("#F8F9FA")
        val surfaceColor = if (isDark) Color.parseColor("#1E1E1E") else Color.parseColor("#FFFFFF")
        val textColor = if (isDark) Color.parseColor("#E3E3E3") else Color.parseColor("#1F1F1F")
        val subTextColor = if (isDark) Color.parseColor("#9E9E9E") else Color.parseColor("#757575")
        val strokeColor = if (isDark) Color.parseColor("#2C2C2C") else Color.parseColor("#E0E0E0")

        val dialog = Dialog(activity, android.R.style.Theme_Light_NoTitleBar)
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(bgColor))
        dialog.window?.statusBarColor = bgColor
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            dialog.window?.navigationBarColor = bgColor
        }

        // State
        var currentFolderId = 0L
        val folderHistory = mutableListOf<BookmarkFolder>()
        var isSelectionMode = false
        val selectedBookmarkIds = mutableSetOf<Long>()
        var currentQuery = ""

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgColor)
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        // --- Normal Header ---
        val normalHeader = RelativeLayout(activity).apply {
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
        }
        normalHeader.addView(btnBack)

        val tvTitle = TextView(activity).apply {
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
        normalHeader.addView(tvTitle)

        val normalActions = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = RelativeLayout.LayoutParams(RelativeLayout.LayoutParams.WRAP_CONTENT, RelativeLayout.LayoutParams.WRAP_CONTENT).apply {
                addRule(RelativeLayout.ALIGN_PARENT_END)
                addRule(RelativeLayout.CENTER_VERTICAL)
            }
        }

        val btnSelect = TextView(activity).apply {
            text = "Select"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(accentColor)
            setPadding(dpToPx(activity, 8), dpToPx(activity, 8), dpToPx(activity, 8), dpToPx(activity, 8))
            setBackgroundResource(getSelectableRes(activity))
        }

        val btnNewFolder = ImageButton(activity).apply {
            setImageResource(R.drawable.ic_folder)
            setColorFilter(accentColor)
            setBackgroundResource(getSelectableRes(activity))
            layoutParams = LinearLayout.LayoutParams(dpToPx(activity, 38), dpToPx(activity, 38))
        }

        val btnAddBookmark = ImageButton(activity).apply {
            setImageResource(R.drawable.ic_add)
            setColorFilter(accentColor)
            setBackgroundResource(getSelectableRes(activity))
            layoutParams = LinearLayout.LayoutParams(dpToPx(activity, 38), dpToPx(activity, 38)).apply {
                marginStart = dpToPx(activity, 4)
            }
        }

        normalActions.addView(btnSelect)
        normalActions.addView(btnNewFolder)
        normalActions.addView(btnAddBookmark)
        normalHeader.addView(normalActions)
        root.addView(normalHeader)

        // --- Selection Mode Header ---
        val selectionHeader = RelativeLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(activity, 64))
            setPadding(dpToPx(activity, 16), 0, dpToPx(activity, 16), 0)
            setBackgroundColor(if (isDark) Color.parseColor("#262626") else Color.parseColor("#E8F0FE"))
            visibility = View.GONE
        }

        val btnCancelSelect = ImageButton(activity).apply {
            id = View.generateViewId()
            setImageResource(R.drawable.ic_close)
            setColorFilter(textColor)
            setBackgroundResource(getSelectableRes(activity))
            layoutParams = RelativeLayout.LayoutParams(dpToPx(activity, 40), dpToPx(activity, 40)).apply {
                addRule(RelativeLayout.ALIGN_PARENT_START)
                addRule(RelativeLayout.CENTER_VERTICAL)
            }
        }
        selectionHeader.addView(btnCancelSelect)

        val tvSelectedCount = TextView(activity).apply {
            text = "0 selected"
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setTextColor(textColor)
            layoutParams = RelativeLayout.LayoutParams(RelativeLayout.LayoutParams.WRAP_CONTENT, RelativeLayout.LayoutParams.WRAP_CONTENT).apply {
                addRule(RelativeLayout.RIGHT_OF, btnCancelSelect.id)
                addRule(RelativeLayout.CENTER_VERTICAL)
                marginStart = dpToPx(activity, 16)
            }
        }
        selectionHeader.addView(tvSelectedCount)

        val selectionActions = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = RelativeLayout.LayoutParams(RelativeLayout.LayoutParams.WRAP_CONTENT, RelativeLayout.LayoutParams.WRAP_CONTENT).apply {
                addRule(RelativeLayout.ALIGN_PARENT_END)
                addRule(RelativeLayout.CENTER_VERTICAL)
            }
        }

        val btnMoveSelected = TextView(activity).apply {
            text = "Move"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(accentColor)
            setPadding(dpToPx(activity, 10), dpToPx(activity, 8), dpToPx(activity, 10), dpToPx(activity, 8))
            setBackgroundResource(getSelectableRes(activity))
        }

        val btnDeleteSelected = ImageButton(activity).apply {
            setImageResource(R.drawable.ic_delete)
            setColorFilter(if (isDark) Color.parseColor("#FF8A80") else Color.parseColor("#D93025"))
            setBackgroundResource(getSelectableRes(activity))
            layoutParams = LinearLayout.LayoutParams(dpToPx(activity, 40), dpToPx(activity, 40))
        }

        selectionActions.addView(btnMoveSelected)
        selectionActions.addView(btnDeleteSelected)
        selectionHeader.addView(selectionActions)
        root.addView(selectionHeader)

        // --- Search Bar ---
        val searchContainer = FrameLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(activity, 48)).apply {
                setMargins(dpToPx(activity, 16), 0, dpToPx(activity, 16), dpToPx(activity, 8))
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(activity, 24).toFloat()
                setColor(surfaceColor)
                setStroke(dpToPx(activity, 1), strokeColor)
            }
        }

        val searchInput = EditText(activity).apply {
            hint = "Search bookmarks..."
            setHintTextColor(subTextColor)
            setTextColor(textColor)
            textSize = 14f
            background = null
            setPadding(dpToPx(activity, 44), 0, dpToPx(activity, 40), 0)
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            isSingleLine = true
        }

        val searchIcon = ImageView(activity).apply {
            setImageResource(R.drawable.ic_search)
            setColorFilter(subTextColor)
            layoutParams = FrameLayout.LayoutParams(dpToPx(activity, 20), dpToPx(activity, 20)).apply {
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
                marginStart = dpToPx(activity, 14)
            }
        }

        val btnClearSearch = ImageView(activity).apply {
            setImageResource(R.drawable.ic_close)
            setColorFilter(subTextColor)
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(dpToPx(activity, 20), dpToPx(activity, 20)).apply {
                gravity = Gravity.CENTER_VERTICAL or Gravity.END
                marginEnd = dpToPx(activity, 14)
            }
            setOnClickListener { searchInput.setText("") }
        }

        searchContainer.addView(searchIcon)
        searchContainer.addView(searchInput)
        searchContainer.addView(btnClearSearch)
        root.addView(searchContainer)

        // --- Interactive Breadcrumb Bar ---
        val breadcrumbScroll = HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(dpToPx(activity, 16), 0, dpToPx(activity, 16), dpToPx(activity, 8))
            }
        }
        val breadcrumbRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        breadcrumbScroll.addView(breadcrumbRow)
        root.addView(breadcrumbScroll)

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
                setImageResource(R.drawable.ic_star)
                setColorFilter(subTextColor)
                layoutParams = LinearLayout.LayoutParams(dpToPx(activity, 64), dpToPx(activity, 64)).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                }
            }
            val emptyTitle = TextView(activity).apply {
                text = "No bookmarks yet"
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
                text = "Tap + to add a bookmark or create a folder"
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
        root.addView(contentFrame)

        var refreshListFunc: (() -> Unit)? = null

        fun updateSelectionHeader() {
            tvSelectedCount.text = "${selectedBookmarkIds.size} selected"
        }

        fun exitSelectionMode() {
            isSelectionMode = false
            selectedBookmarkIds.clear()
            selectionHeader.visibility = View.GONE
            normalHeader.visibility = View.VISIBLE
            searchContainer.visibility = View.VISIBLE
            breadcrumbScroll.visibility = View.VISIBLE
            refreshListFunc?.invoke()
        }

        fun enterSelectionMode(initialSelectedId: Long? = null) {
            isSelectionMode = true
            selectedBookmarkIds.clear()
            if (initialSelectedId != null) selectedBookmarkIds.add(initialSelectedId)
            normalHeader.visibility = View.GONE
            selectionHeader.visibility = View.VISIBLE
            searchContainer.visibility = View.GONE
            breadcrumbScroll.visibility = View.GONE
            updateSelectionHeader()
            refreshListFunc?.invoke()
        }

        btnSelect.setOnClickListener {
            activity.triggerStrongHaptic(it)
            enterSelectionMode()
        }

        btnCancelSelect.setOnClickListener {
            activity.triggerStrongHaptic(it)
            exitSelectionMode()
        }

        btnDeleteSelected.setOnClickListener {
            if (selectedBookmarkIds.isEmpty()) return@setOnClickListener
            activity.triggerStrongHaptic(it, isHeavy = true)
            AlertDialog.Builder(activity)
                .setTitle("Delete ${selectedBookmarkIds.size} bookmarks?")
                .setMessage("These bookmarks will be permanently removed.")
                .setPositiveButton("Delete") { _, _ ->
                    HazeDatabase.getInstance(activity).deleteBookmarks(selectedBookmarkIds.toList())
                    Toast.makeText(activity, "${selectedBookmarkIds.size} bookmarks deleted", Toast.LENGTH_SHORT).show()
                    exitSelectionMode()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        btnMoveSelected.setOnClickListener {
            if (selectedBookmarkIds.isEmpty()) return@setOnClickListener
            activity.triggerStrongHaptic(it)
            showMoveFolderPicker(activity, selectedBookmarkIds.toList()) {
                exitSelectionMode()
                refreshListFunc?.invoke()
            }
        }

        fun updateBreadcrumbs() {
            breadcrumbRow.removeAllViews()

            // Root button
            val rootItem = TextView(activity).apply {
                text = "Mobile Bookmarks"
                textSize = 13f
                setTypeface(null, if (folderHistory.isEmpty()) Typeface.BOLD else Typeface.NORMAL)
                setTextColor(if (folderHistory.isEmpty()) textColor else accentColor)
                setPadding(dpToPx(activity, 4), dpToPx(activity, 4), dpToPx(activity, 4), dpToPx(activity, 4))
                setOnClickListener {
                    if (folderHistory.isNotEmpty()) {
                        activity.triggerStrongHaptic(it)
                        folderHistory.clear()
                        currentFolderId = 0L
                        refreshListFunc?.invoke()
                    }
                }
            }
            breadcrumbRow.addView(rootItem)

            for (i in folderHistory.indices) {
                val f = folderHistory[i]
                val sep = TextView(activity).apply {
                    text = " › "
                    textSize = 13f
                    setTextColor(subTextColor)
                }
                breadcrumbRow.addView(sep)

                val isLast = i == folderHistory.size - 1
                val folderTv = TextView(activity).apply {
                    text = f.name
                    textSize = 13f
                    setTypeface(null, if (isLast) Typeface.BOLD else Typeface.NORMAL)
                    setTextColor(if (isLast) textColor else accentColor)
                    setPadding(dpToPx(activity, 4), dpToPx(activity, 4), dpToPx(activity, 4), dpToPx(activity, 4))
                    setOnClickListener {
                        if (!isLast) {
                            activity.triggerStrongHaptic(it)
                            while (folderHistory.size > i + 1) {
                                folderHistory.removeAt(folderHistory.size - 1)
                            }
                            currentFolderId = f.id
                            refreshListFunc?.invoke()
                        }
                    }
                }
                breadcrumbRow.addView(folderTv)
            }
        }

        fun refreshList() {
            val db = HazeDatabase.getInstance(activity)
            val query = currentQuery.trim()

            if (query.isNotEmpty()) {
                breadcrumbScroll.visibility = View.GONE
                val results = db.searchBookmarks(query)
                if (results.isEmpty()) {
                    emptyView.visibility = View.VISIBLE
                    recyclerView.visibility = View.GONE
                } else {
                    emptyView.visibility = View.GONE
                    recyclerView.visibility = View.VISIBLE
                    recyclerView.adapter = BookmarksRecyclerAdapter(
                        activity = activity,
                        folders = emptyList(),
                        bookmarks = results,
                        isSelectionMode = isSelectionMode,
                        selectedIds = selectedBookmarkIds,
                        onFolderClick = {},
                        onBookmarkClick = { b ->
                            if (isSelectionMode) {
                                activity.triggerStrongHaptic()
                                if (selectedBookmarkIds.contains(b.id)) selectedBookmarkIds.remove(b.id) else selectedBookmarkIds.add(b.id)
                                updateSelectionHeader()
                                recyclerView.adapter?.notifyDataSetChanged()
                            } else {
                                activity.triggerStrongHaptic()
                                dialog.dismiss()
                                onNavigate(b.url)
                            }
                        },
                        onFolderOptions = {},
                        onBookmarkOptions = { b ->
                            showBookmarkOptions(activity, b, onNavigate, { refreshListFunc?.invoke() }, { enterSelectionMode(b.id) })
                        },
                        isDark = isDark,
                        textColor = textColor,
                        subTextColor = subTextColor,
                        accentColor = accentColor
                    )
                }
            } else {
                breadcrumbScroll.visibility = if (isSelectionMode) View.GONE else View.VISIBLE
                updateBreadcrumbs()

                val subfolders = db.getSubFolders(currentFolderId)
                val bookmarks = db.getBookmarks(currentFolderId)

                if (subfolders.isEmpty() && bookmarks.isEmpty()) {
                    emptyView.visibility = View.VISIBLE
                    recyclerView.visibility = View.GONE
                } else {
                    emptyView.visibility = View.GONE
                    recyclerView.visibility = View.VISIBLE
                    recyclerView.adapter = BookmarksRecyclerAdapter(
                        activity = activity,
                        folders = subfolders,
                        bookmarks = bookmarks,
                        isSelectionMode = isSelectionMode,
                        selectedIds = selectedBookmarkIds,
                        onFolderClick = { f ->
                            activity.triggerStrongHaptic()
                            folderHistory.add(f)
                            currentFolderId = f.id
                            refreshList()
                        },
                        onBookmarkClick = { b ->
                            if (isSelectionMode) {
                                activity.triggerStrongHaptic()
                                if (selectedBookmarkIds.contains(b.id)) selectedBookmarkIds.remove(b.id) else selectedBookmarkIds.add(b.id)
                                updateSelectionHeader()
                                recyclerView.adapter?.notifyDataSetChanged()
                            } else {
                                activity.triggerStrongHaptic()
                                dialog.dismiss()
                                onNavigate(b.url)
                            }
                        },
                        onFolderOptions = { f ->
                            showFolderOptions(activity, f) { refreshList() }
                        },
                        onBookmarkOptions = { b ->
                            showBookmarkOptions(activity, b, onNavigate, { refreshList() }, { enterSelectionMode(b.id) })
                        },
                        isDark = isDark,
                        textColor = textColor,
                        subTextColor = subTextColor,
                        accentColor = accentColor
                    )
                }
            }
        }
        refreshListFunc = { refreshList() }

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

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                currentQuery = s?.toString() ?: ""
                btnClearSearch.visibility = if (currentQuery.isNotEmpty()) View.VISIBLE else View.GONE
                refreshList()
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

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
                Toast.makeText(activity, "Bookmark added", Toast.LENGTH_SHORT).show()
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

    private fun showBookmarkOptions(
        activity: MainActivity,
        bookmark: BookmarkItem,
        onNavigate: (String) -> Unit,
        onUpdated: () -> Unit,
        onEnterSelect: () -> Unit
    ) {
        val options = arrayOf("Open in new tab", "Edit", "Move to folder", "Select items", "Share", "Delete")
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
                        showMoveFolderPicker(activity, listOf(bookmark.id)) {
                            onUpdated()
                        }
                    }
                    3 -> { // Select
                        onEnterSelect()
                    }
                    4 -> { // Share
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, bookmark.title)
                            putExtra(Intent.EXTRA_TEXT, bookmark.url)
                        }
                        activity.startActivity(Intent.createChooser(intent, "Share bookmark"))
                    }
                    5 -> { // Delete
                        HazeDatabase.getInstance(activity).deleteBookmark(bookmark.id)
                        Toast.makeText(activity, "Bookmark deleted", Toast.LENGTH_SHORT).show()
                        onUpdated()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showMoveFolderPicker(activity: MainActivity, bookmarkIds: List<Long>, onMoved: () -> Unit) {
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
                HazeDatabase.getInstance(activity).moveBookmarks(bookmarkIds, targetFolderId)
                Toast.makeText(activity, "Moved ${bookmarkIds.size} item(s) to ${folderNames[fIdx]}", Toast.LENGTH_SHORT).show()
                onMoved()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private class BookmarksRecyclerAdapter(
        private val activity: MainActivity,
        private val folders: List<BookmarkFolder>,
        private val bookmarks: List<BookmarkItem>,
        private val isSelectionMode: Boolean,
        private val selectedIds: Set<Long>,
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
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(activity, 64))
                setPadding(dpToPx(activity, 16), 0, dpToPx(activity, 12), 0)
                setBackgroundResource(getSelectableRes(activity))
            }

            val checkbox = CheckBox(activity).apply {
                isClickable = false
                isFocusable = false
                layoutParams = LinearLayout.LayoutParams(dpToPx(activity, 32), dpToPx(activity, 32)).apply {
                    marginEnd = dpToPx(activity, 8)
                }
            }

            val icon = ImageView(activity).apply {
                layoutParams = LinearLayout.LayoutParams(dpToPx(activity, 32), dpToPx(activity, 32)).apply {
                    marginEnd = dpToPx(activity, 14)
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

            row.addView(checkbox)
            row.addView(icon)
            row.addView(textCol)
            row.addView(btnMore)

            return BookmarkRowHolder(row, checkbox, icon, titleTv, subTv, btnMore)
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val h = holder as BookmarkRowHolder
            if (position < folders.size) {
                val f = folders[position]
                h.checkbox.visibility = View.GONE
                h.icon.setImageResource(R.drawable.ic_folder)
                h.icon.setColorFilter(accentColor)
                h.titleTv.text = f.name
                val count = HazeDatabase.getInstance(activity).getBookmarks(f.id).size
                h.subTv.text = "$count bookmarks"
                h.subTv.visibility = View.VISIBLE

                h.itemView.setOnClickListener { onFolderClick(f) }
                h.itemView.setOnLongClickListener { onFolderOptions(f); true }
                h.btnMore.setOnClickListener { onFolderOptions(f) }
            } else {
                val b = bookmarks[position - folders.size]
                val domain = HazeFaviconManager.getDomain(b.url)

                if (isSelectionMode) {
                    h.checkbox.visibility = View.VISIBLE
                    h.checkbox.isChecked = selectedIds.contains(b.id)
                    h.btnMore.visibility = View.GONE
                } else {
                    h.checkbox.visibility = View.GONE
                    h.btnMore.visibility = View.VISIBLE
                }

                h.icon.setColorFilter(0) // clear tint for bitmap favicon
                HazeFaviconManager.loadFavicon(activity, b.url, h.icon, fallbackText = domain, isDark = isDark)

                h.titleTv.text = b.title
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
            val checkbox: CheckBox,
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
