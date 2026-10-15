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
import java.text.SimpleDateFormat
import java.util.*

object HazeHistoryManager {

    fun showHistoryDialog(activity: MainActivity, onNavigate: (String) -> Unit) {
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

        // State
        var isSelectionMode = false
        val selectedIds = mutableSetOf<Long>()
        var currentQuery = ""
        var currentFilter = "All Time"

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
            setOnClickListener {
                activity.triggerStrongHaptic(it)
                dialog.dismiss()
            }
        }
        normalHeader.addView(btnBack)

        val tvTitle = TextView(activity).apply {
            text = "History"
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
            setPadding(dpToPx(activity, 12), dpToPx(activity, 8), dpToPx(activity, 8), dpToPx(activity, 8))
            setBackgroundResource(getSelectableRes(activity))
        }

        val btnClearData = TextView(activity).apply {
            text = "Clear Data"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(if (isDark) Color.parseColor("#FF8A80") else Color.parseColor("#D93025"))
            setPadding(dpToPx(activity, 10), dpToPx(activity, 8), dpToPx(activity, 8), dpToPx(activity, 8))
            setBackgroundResource(getSelectableRes(activity))
        }

        normalActions.addView(btnSelect)
        normalActions.addView(btnClearData)
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

        val btnSelectAll = TextView(activity).apply {
            text = "Select All"
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
            setPadding(dpToPx(activity, 8), dpToPx(activity, 8), dpToPx(activity, 8), dpToPx(activity, 8))
            layoutParams = LinearLayout.LayoutParams(dpToPx(activity, 40), dpToPx(activity, 40))
        }

        selectionActions.addView(btnSelectAll)
        selectionActions.addView(btnDeleteSelected)
        selectionHeader.addView(selectionActions)
        root.addView(selectionHeader)

        // --- Search & Filter Bar ---
        val searchAndFilterRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                setMargins(dpToPx(activity, 16), 0, dpToPx(activity, 16), dpToPx(activity, 12))
            }
        }

        val searchContainer = FrameLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(0, dpToPx(activity, 48), 1f).apply {
                marginEnd = dpToPx(activity, 8)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(activity, 24).toFloat()
                setColor(surfaceColor)
                setStroke(dpToPx(activity, 1), strokeColor)
            }
        }

        val searchInput = EditText(activity).apply {
            hint = "Search history..."
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
        searchAndFilterRow.addView(searchContainer)

        val btnFilter = LinearLayout(activity).apply {
            id = View.generateViewId()
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(activity, 24).toFloat()
                setColor(surfaceColor)
                setStroke(dpToPx(activity, 1), strokeColor)
            }
            setPadding(dpToPx(activity, 12), 0, dpToPx(activity, 12), 0)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dpToPx(activity, 48))
        }
        val tvFilter = TextView(activity).apply {
            text = "All Time"
            textSize = 13f
            setTextColor(textColor)
            setTypeface(null, Typeface.BOLD)
        }
        val ivFilterArrow = ImageView(activity).apply {
            setImageResource(R.drawable.ic_chevron_down)
            setColorFilter(textColor)
            layoutParams = LinearLayout.LayoutParams(dpToPx(activity, 16), dpToPx(activity, 16)).apply {
                marginStart = dpToPx(activity, 4)
            }
        }
        btnFilter.addView(tvFilter)
        btnFilter.addView(ivFilterArrow)
        searchAndFilterRow.addView(btnFilter)
        
        root.addView(searchAndFilterRow)

        // --- RecyclerView & Empty View ---
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
                setImageResource(R.drawable.ic_history)
                setColorFilter(subTextColor)
                layoutParams = LinearLayout.LayoutParams(dpToPx(activity, 64), dpToPx(activity, 64)).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                }
            }
            val emptyTitle = TextView(activity).apply {
                text = "No browsing history"
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
                text = "Websites you visit will appear here."
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

        btnFilter.setOnClickListener {
            val popup = PopupMenu(activity, btnFilter)
            val filters = listOf("All Time", "Today", "Yesterday", "Older")
            filters.forEachIndexed { index, title ->
                popup.menu.add(android.view.Menu.NONE, index, android.view.Menu.NONE, title)
            }
            popup.setOnMenuItemClickListener { item ->
                currentFilter = filters[item.itemId]
                tvFilter.text = currentFilter
                refreshListFunc?.invoke()
                true
            }
            popup.show()
        }

        fun updateSelectionHeader() {
            tvSelectedCount.text = "${selectedIds.size} selected"
        }

        fun exitSelectionMode() {
            isSelectionMode = false
            selectedIds.clear()
            selectionHeader.visibility = View.GONE
            normalHeader.visibility = View.VISIBLE
            searchContainer.visibility = View.VISIBLE
            refreshListFunc?.invoke()
        }

        fun enterSelectionMode(initialSelectedId: Long? = null) {
            isSelectionMode = true
            selectedIds.clear()
            if (initialSelectedId != null) selectedIds.add(initialSelectedId)
            normalHeader.visibility = View.GONE
            selectionHeader.visibility = View.VISIBLE
            searchContainer.visibility = View.GONE
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
            if (selectedIds.isEmpty()) return@setOnClickListener
            activity.triggerStrongHaptic(it, isHeavy = true)
            AlertDialog.Builder(activity)
                .setTitle("Delete ${selectedIds.size} items?")
                .setMessage("These entries will be permanently removed from your browsing history.")
                .setPositiveButton("Delete") { _, _ ->
                    HazeDatabase.getInstance(activity).deleteHistoryItems(selectedIds.toList())
                    Toast.makeText(activity, "${selectedIds.size} entries deleted", Toast.LENGTH_SHORT).show()
                    exitSelectionMode()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        btnClearData.setOnClickListener {
            activity.triggerStrongHaptic(it, isHeavy = true)
            showClearHistoryDialog(activity) {
                exitSelectionMode()
                refreshListFunc?.invoke()
            }
        }

        fun refreshList() {
            val db = HazeDatabase.getInstance(activity)
            val query = currentQuery.trim()

            if (query.isNotEmpty()) {
                val results = db.searchHistory(query)
                if (results.isEmpty()) {
                    emptyView.visibility = View.VISIBLE
                    recyclerView.visibility = View.GONE
                } else {
                    emptyView.visibility = View.GONE
                    recyclerView.visibility = View.VISIBLE
                    recyclerView.adapter = HistoryItemAdapter(
                        activity = activity,
                        items = results,
                        isSelectionMode = isSelectionMode,
                        selectedIds = selectedIds,
                        onItemClick = { item ->
                            if (isSelectionMode) {
                                activity.triggerStrongHaptic()
                                if (selectedIds.contains(item.id)) selectedIds.remove(item.id) else selectedIds.add(item.id)
                                updateSelectionHeader()
                                recyclerView.adapter?.notifyDataSetChanged()
                            } else {
                                activity.triggerStrongHaptic()
                                dialog.dismiss()
                                onNavigate(item.url)
                            }
                        },
                        onItemLongClick = { item ->
                            if (!isSelectionMode) {
                                activity.triggerStrongHaptic(isHeavy = true)
                                showItemOptionsMenu(activity, item, onNavigate, { refreshListFunc?.invoke() }, { enterSelectionMode(item.id) })
                            }
                        },
                        onItemDelete = { item ->
                            activity.triggerStrongHaptic()
                            db.deleteHistoryItem(item.id)
                            refreshListFunc?.invoke()
                        },
                        isDark = isDark,
                        textColor = textColor,
                        subTextColor = subTextColor,
                        accentColor = accentColor
                    )
                }
            } else {
                val allGrouped = db.getHistoryGrouped()
                val grouped = when (currentFilter) {
                    "Today" -> allGrouped.filterKeys { it == "Today" }
                    "Yesterday" -> allGrouped.filterKeys { it == "Yesterday" }
                    "Older" -> allGrouped.filterKeys { it != "Today" && it != "Yesterday" }
                    else -> allGrouped
                }

                if (grouped.isEmpty()) {
                    emptyView.visibility = View.VISIBLE
                    recyclerView.visibility = View.GONE
                } else {
                    emptyView.visibility = View.GONE
                    recyclerView.visibility = View.VISIBLE
                    recyclerView.adapter = HistoryGroupedRecyclerAdapter(
                        activity = activity,
                        grouped = grouped,
                        isSelectionMode = isSelectionMode,
                        selectedIds = selectedIds,
                        onItemClick = { item ->
                            if (isSelectionMode) {
                                activity.triggerStrongHaptic()
                                if (selectedIds.contains(item.id)) selectedIds.remove(item.id) else selectedIds.add(item.id)
                                updateSelectionHeader()
                                recyclerView.adapter?.notifyDataSetChanged()
                            } else {
                                activity.triggerStrongHaptic()
                                dialog.dismiss()
                                onNavigate(item.url)
                            }
                        },
                        onItemLongClick = { item ->
                            if (!isSelectionMode) {
                                activity.triggerStrongHaptic(isHeavy = true)
                                showItemOptionsMenu(activity, item, onNavigate, { refreshListFunc?.invoke() }, { enterSelectionMode(item.id) })
                            }
                        },
                        onItemDelete = { item ->
                            activity.triggerStrongHaptic()
                            db.deleteHistoryItem(item.id)
                            refreshListFunc?.invoke()
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

        btnSelectAll.setOnClickListener {
            activity.triggerStrongHaptic(it)
            val db = HazeDatabase.getInstance(activity)
            val all = if (currentQuery.isNotEmpty()) db.searchHistory(currentQuery) else db.getAllHistory()
            if (selectedIds.size == all.size) {
                selectedIds.clear()
            } else {
                selectedIds.clear()
                selectedIds.addAll(all.map { it.id })
            }
            updateSelectionHeader()
            recyclerView.adapter?.notifyDataSetChanged()
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

    private fun showItemOptionsMenu(
        activity: MainActivity,
        item: HistoryItem,
        onNavigate: (String) -> Unit,
        onRefresh: () -> Unit,
        onEnterSelect: () -> Unit
    ) {
        val options = arrayOf(
            "Open in new tab",
            "Open in private tab",
            "Select items",
            "Copy URL",
            "Share link",
            "Delete"
        )

        AlertDialog.Builder(activity)
            .setTitle(item.title)
            .setItems(options) { _, which ->
                activity.triggerStrongHaptic()
                when (which) {
                    0 -> {
                        activity.addNewTab(item.url)
                        Toast.makeText(activity, "Opened in new tab", Toast.LENGTH_SHORT).show()
                    }
                    1 -> {
                        activity.openPrivateTab(item.url)
                        Toast.makeText(activity, "Opened in private tab", Toast.LENGTH_SHORT).show()
                    }
                    2 -> {
                        onEnterSelect()
                    }
                    3 -> {
                        val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("URL", item.url))
                        Toast.makeText(activity, "URL copied to clipboard", Toast.LENGTH_SHORT).show()
                    }
                    4 -> {
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, item.title)
                            putExtra(Intent.EXTRA_TEXT, item.url)
                        }
                        activity.startActivity(Intent.createChooser(intent, "Share link"))
                    }
                    5 -> {
                        HazeDatabase.getInstance(activity).deleteHistoryItem(item.id)
                        Toast.makeText(activity, "Deleted", Toast.LENGTH_SHORT).show()
                        onRefresh()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showClearHistoryDialog(activity: MainActivity, onCleared: () -> Unit) {
        HazeClearDataManager.showClearDataDialog(activity, onCleared)
    }

    // --- Adapters ---

    private class HistoryGroupedRecyclerAdapter(
        private val activity: MainActivity,
        private val grouped: Map<String, List<HistoryItem>>,
        private val isSelectionMode: Boolean,
        private val selectedIds: Set<Long>,
        private val onItemClick: (HistoryItem) -> Unit,
        private val onItemLongClick: (HistoryItem) -> Unit,
        private val onItemDelete: (HistoryItem) -> Unit,
        private val isDark: Boolean,
        private val textColor: Int,
        private val subTextColor: Int,
        private val accentColor: Int
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private val items = mutableListOf<Any>()
        private val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())

        init {
            for ((section, list) in grouped) {
                items.add(section)
                items.addAll(list)
            }
        }

        override fun getItemViewType(position: Int): Int {
            return if (items[position] is String) 0 else 1
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            if (viewType == 0) {
                val tv = TextView(activity).apply {
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    setPadding(dpToPx(activity, 20), dpToPx(activity, 16), dpToPx(activity, 20), dpToPx(activity, 6))
                    textSize = 13f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(accentColor)
                }
                return HeaderHolder(tv)
            } else {
                val row = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(activity, 68))
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

                val favicon = ImageView(activity).apply {
                    layoutParams = LinearLayout.LayoutParams(dpToPx(activity, 36), dpToPx(activity, 36)).apply {
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

                val subRow = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    val domainTv = TextView(activity).apply {
                        textSize = 12f
                        setTextColor(subTextColor)
                        isSingleLine = true
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    }
                    val timeTv = TextView(activity).apply {
                        textSize = 11f
                        setTextColor(subTextColor)
                        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                            marginStart = dpToPx(activity, 8)
                        }
                    }
                    addView(domainTv)
                    addView(timeTv)
                }

                textCol.addView(titleTv)
                textCol.addView(subRow)

                val btnDelete = ImageButton(activity).apply {
                    setImageResource(R.drawable.ic_close)
                    setColorFilter(subTextColor)
                    setBackgroundResource(getSelectableRes(activity))
                    layoutParams = LinearLayout.LayoutParams(dpToPx(activity, 32), dpToPx(activity, 32))
                }

                row.addView(checkbox)
                row.addView(favicon)
                row.addView(textCol)
                row.addView(btnDelete)

                return EntryHolder(row, checkbox, favicon, titleTv, subRow.getChildAt(0) as TextView, subRow.getChildAt(1) as TextView, btnDelete)
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (holder is HeaderHolder) {
                holder.textView.text = items[position] as String
            } else if (holder is EntryHolder) {
                val item = items[position] as HistoryItem
                holder.titleTv.text = item.title
                val domain = HazeFaviconManager.getDomain(item.url)
                val visitStr = if (item.visitCount > 1) " • ${item.visitCount} visits" else ""
                holder.domainTv.text = "$domain$visitStr"
                holder.timeTv.text = timeFormat.format(Date(item.timestamp))

                if (isSelectionMode) {
                    holder.checkbox.visibility = View.VISIBLE
                    holder.checkbox.isChecked = selectedIds.contains(item.id)
                    holder.btnDelete.visibility = View.GONE
                } else {
                    holder.checkbox.visibility = View.GONE
                    holder.btnDelete.visibility = View.VISIBLE
                }

                // Load Favicon asynchronously
                HazeFaviconManager.loadFavicon(activity, item.url, holder.favicon, fallbackText = domain, isDark = isDark)

                holder.itemView.setOnClickListener { onItemClick(item) }
                holder.itemView.setOnLongClickListener {
                    onItemLongClick(item)
                    true
                }
                holder.btnDelete.setOnClickListener {
                    onItemDelete(item)
                }
            }
        }

        override fun getItemCount(): Int = items.size
        class HeaderHolder(val textView: TextView) : RecyclerView.ViewHolder(textView)
        class EntryHolder(
            val view: View,
            val checkbox: CheckBox,
            val favicon: ImageView,
            val titleTv: TextView,
            val domainTv: TextView,
            val timeTv: TextView,
            val btnDelete: ImageButton
        ) : RecyclerView.ViewHolder(view)
    }

    private class HistoryItemAdapter(
        private val activity: MainActivity,
        private val items: List<HistoryItem>,
        private val isSelectionMode: Boolean,
        private val selectedIds: Set<Long>,
        private val onItemClick: (HistoryItem) -> Unit,
        private val onItemLongClick: (HistoryItem) -> Unit,
        private val onItemDelete: (HistoryItem) -> Unit,
        private val isDark: Boolean,
        private val textColor: Int,
        private val subTextColor: Int,
        private val accentColor: Int
    ) : RecyclerView.Adapter<HistoryItemAdapter.EntryHolder>() {

        private val timeFormat = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())

        class EntryHolder(
            val view: View,
            val checkbox: CheckBox,
            val favicon: ImageView,
            val titleTv: TextView,
            val domainTv: TextView,
            val timeTv: TextView,
            val btnDelete: ImageButton
        ) : RecyclerView.ViewHolder(view)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EntryHolder {
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(activity, 68))
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

            val favicon = ImageView(activity).apply {
                layoutParams = LinearLayout.LayoutParams(dpToPx(activity, 36), dpToPx(activity, 36)).apply {
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

            val subRow = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                val domainTv = TextView(activity).apply {
                    textSize = 12f
                    setTextColor(subTextColor)
                    isSingleLine = true
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                val timeTv = TextView(activity).apply {
                    textSize = 11f
                    setTextColor(subTextColor)
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                        marginStart = dpToPx(activity, 8)
                    }
                }
                addView(domainTv)
                addView(timeTv)
            }

            textCol.addView(titleTv)
            textCol.addView(subRow)

            val btnDelete = ImageButton(activity).apply {
                setImageResource(R.drawable.ic_close)
                setColorFilter(subTextColor)
                setBackgroundResource(getSelectableRes(activity))
                layoutParams = LinearLayout.LayoutParams(dpToPx(activity, 32), dpToPx(activity, 32))
            }

            row.addView(checkbox)
            row.addView(favicon)
            row.addView(textCol)
            row.addView(btnDelete)

            return EntryHolder(row, checkbox, favicon, titleTv, subRow.getChildAt(0) as TextView, subRow.getChildAt(1) as TextView, btnDelete)
        }

        override fun onBindViewHolder(holder: EntryHolder, position: Int) {
            val item = items[position]
            holder.titleTv.text = item.title
            val domain = HazeFaviconManager.getDomain(item.url)
            val visitStr = if (item.visitCount > 1) " • ${item.visitCount} visits" else ""
            holder.domainTv.text = "$domain$visitStr"
            holder.timeTv.text = timeFormat.format(Date(item.timestamp))

            if (isSelectionMode) {
                holder.checkbox.visibility = View.VISIBLE
                holder.checkbox.isChecked = selectedIds.contains(item.id)
                holder.btnDelete.visibility = View.GONE
            } else {
                holder.checkbox.visibility = View.GONE
                holder.btnDelete.visibility = View.VISIBLE
            }

            HazeFaviconManager.loadFavicon(activity, item.url, holder.favicon, fallbackText = domain, isDark = isDark)

            holder.itemView.setOnClickListener { onItemClick(item) }
            holder.itemView.setOnLongClickListener {
                onItemLongClick(item)
                true
            }
            holder.btnDelete.setOnClickListener {
                onItemDelete(item)
            }
        }

        override fun getItemCount(): Int = items.size
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
