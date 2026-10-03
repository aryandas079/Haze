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
        val isDark = activity.getSharedPreferences("HazePrefs", Context.MODE_PRIVATE).getBoolean("is_dark", false)
        val accentColor = Color.parseColor(activity.getSharedPreferences("HazePrefs", Context.MODE_PRIVATE).getString("accent_color", "#0A56D0"))
        val bgColor = if (isDark) Color.parseColor("#181818") else Color.parseColor("#FAFAFA")
        val cardBgColor = if (isDark) Color.parseColor("#222222") else Color.parseColor("#FFFFFF")
        val textColor = if (isDark) Color.WHITE else Color.parseColor("#202124")
        val subTextColor = if (isDark) Color.parseColor("#9AA0A6") else Color.parseColor("#5F6368")
        val strokeColor = if (isDark) Color.parseColor("#333333") else Color.parseColor("#E0E0E0")

        val dialog = Dialog(activity, android.R.style.Theme_Light_NoTitleBar)
        dialog.window?.statusBarColor = bgColor

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bgColor)
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }

        // Header Bar
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
            text = "Browsing History"
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

        var refreshListAction: (() -> Unit)? = null

        val btnClear = TextView(activity).apply {
            text = "Clear Data"
            textSize = 14f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.parseColor("#EA4335"))
            setPadding(dpToPx(activity, 8), dpToPx(activity, 6), dpToPx(activity, 8), dpToPx(activity, 6))
            setBackgroundResource(getSelectableRes(activity))
            layoutParams = RelativeLayout.LayoutParams(RelativeLayout.LayoutParams.WRAP_CONTENT, RelativeLayout.LayoutParams.WRAP_CONTENT).apply {
                addRule(RelativeLayout.ALIGN_PARENT_END)
                addRule(RelativeLayout.CENTER_VERTICAL)
            }
            setOnClickListener {
                activity.triggerStrongHaptic(it, true)
                showClearHistoryOptions(activity) {
                    refreshListAction?.invoke()
                }
            }
        }
        header.addView(btnClear)
        root.addView(header)

        // Search Bar
        val searchContainer = FrameLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(activity, 46)).apply {
                setMargins(dpToPx(activity, 16), 0, dpToPx(activity, 16), dpToPx(activity, 12))
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(activity, 24).toFloat()
                setColor(cardBgColor)
                setStroke(dpToPx(activity, 1), strokeColor)
            }
        }

        val searchInput = EditText(activity).apply {
            hint = "Search history..."
            setHintTextColor(subTextColor)
            setTextColor(textColor)
            textSize = 14f
            background = null
            setPadding(dpToPx(activity, 42), 0, dpToPx(activity, 36), 0)
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

        // Content Area: RecyclerView & Empty View
        val contentFrame = FrameLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
        }

        val emptyView = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)

            val emptyIcon = ImageView(activity).apply {
                setImageResource(R.drawable.ic_history)
                setColorFilter(subTextColor)
                layoutParams = LinearLayout.LayoutParams(dpToPx(activity, 64), dpToPx(activity, 64))
            }
            val emptyText = TextView(activity).apply {
                text = "No browsing history"
                textSize = 16f
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

        fun refreshList(query: String = "") {
            val db = HazeDatabase.getInstance(activity)
            if (query.trim().isNotEmpty()) {
                val results = db.searchHistory(query.trim())
                if (results.isEmpty()) {
                    emptyView.visibility = View.VISIBLE
                    recyclerView.visibility = View.GONE
                } else {
                    emptyView.visibility = View.GONE
                    recyclerView.visibility = View.VISIBLE
                    recyclerView.adapter = HistorySearchAdapter(activity, results, { item ->
                        activity.triggerStrongHaptic()
                        dialog.dismiss()
                        onNavigate(item.url)
                    }, { item ->
                        db.deleteHistoryItem(item.id)
                        refreshList(searchInput.text.toString())
                    }, isDark, textColor, subTextColor, cardBgColor)
                }
            } else {
                val grouped = db.getHistoryGrouped()
                if (grouped.isEmpty()) {
                    emptyView.visibility = View.VISIBLE
                    recyclerView.visibility = View.GONE
                } else {
                    emptyView.visibility = View.GONE
                    recyclerView.visibility = View.VISIBLE
                    recyclerView.adapter = HistoryGroupedAdapter(activity, grouped, { item ->
                        activity.triggerStrongHaptic()
                        dialog.dismiss()
                        onNavigate(item.url)
                    }, { item ->
                        db.deleteHistoryItem(item.id)
                        refreshList()
                    }, isDark, textColor, subTextColor, cardBgColor, accentColor)
                }
            }
        }
        refreshListAction = { refreshList() }

        searchInput.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString() ?: ""
                btnClearSearch.visibility = if (q.isNotEmpty()) View.VISIBLE else View.GONE
                refreshList(q)
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        refreshList()
        dialog.setContentView(root)
        dialog.show()
    }

    private fun showClearHistoryOptions(activity: MainActivity, onCleared: () -> Unit) {
        val options = arrayOf("Last hour", "Today", "All time")
        AlertDialog.Builder(activity)
            .setTitle("Clear Browsing History")
            .setItems(options) { _, which ->
                activity.triggerStrongHaptic(isHeavy = true)
                val db = HazeDatabase.getInstance(activity)
                when (which) {
                    0 -> { // Last hour
                        val oneHourAgo = System.currentTimeMillis() - (60 * 60 * 1000)
                        db.clearHistoryRange(oneHourAgo)
                        Toast.makeText(activity, "Cleared history from the last hour", Toast.LENGTH_SHORT).show()
                    }
                    1 -> { // Today
                        val cal = Calendar.getInstance()
                        cal.set(Calendar.HOUR_OF_DAY, 0)
                        cal.set(Calendar.MINUTE, 0)
                        cal.set(Calendar.SECOND, 0)
                        cal.set(Calendar.MILLISECOND, 0)
                        db.clearHistoryRange(cal.timeInMillis)
                        Toast.makeText(activity, "Cleared today's history", Toast.LENGTH_SHORT).show()
                    }
                    2 -> { // All time
                        db.clearAllHistory()
                        Toast.makeText(activity, "All browsing history cleared", Toast.LENGTH_SHORT).show()
                    }
                }
                onCleared()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showHistoryItemOptions(activity: MainActivity, item: HistoryItem, onDelete: () -> Unit, onNavigate: (String) -> Unit) {
        val options = arrayOf("Open in new tab", "Open in private tab", "Copy URL", "Share", "Delete")
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
                        val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("URL", item.url))
                        Toast.makeText(activity, "URL copied to clipboard", Toast.LENGTH_SHORT).show()
                    }
                    3 -> {
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, item.title)
                            putExtra(Intent.EXTRA_TEXT, item.url)
                        }
                        activity.startActivity(Intent.createChooser(intent, "Share link"))
                    }
                    4 -> {
                        onDelete()
                        Toast.makeText(activity, "Entry deleted", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private class HistoryGroupedAdapter(
        private val activity: MainActivity,
        private val grouped: Map<String, List<HistoryItem>>,
        private val onItemClick: (HistoryItem) -> Unit,
        private val onItemDelete: (HistoryItem) -> Unit,
        private val isDark: Boolean,
        private val textColor: Int,
        private val subTextColor: Int,
        private val cardBgColor: Int,
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
                    setPadding(dpToPx(activity, 20), dpToPx(activity, 14), dpToPx(activity, 20), dpToPx(activity, 6))
                    textSize = 13f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(accentColor)
                }
                return HeaderViewHolder(tv)
            } else {
                val row = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(activity, 62))
                    setPadding(dpToPx(activity, 16), 0, dpToPx(activity, 12), 0)
                    setBackgroundResource(getSelectableRes(activity))
                }

                val icon = ImageView(activity).apply {
                    layoutParams = LinearLayout.LayoutParams(dpToPx(activity, 24), dpToPx(activity, 24)).apply {
                        marginEnd = dpToPx(activity, 14)
                    }
                    setImageResource(R.drawable.ic_globe)
                    setColorFilter(subTextColor)
                }

                val textCol = LinearLayout(activity).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }

                val titleTv = TextView(activity).apply {
                    id = View.generateViewId()
                    textSize = 15f
                    setTextColor(textColor)
                    isSingleLine = true
                }

                val subRow = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    val urlTv = TextView(activity).apply {
                        id = View.generateViewId()
                        textSize = 12f
                        setTextColor(subTextColor)
                        isSingleLine = true
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    }
                    val timeTv = TextView(activity).apply {
                        id = View.generateViewId()
                        textSize = 11f
                        setTextColor(subTextColor)
                        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                            marginStart = dpToPx(activity, 8)
                        }
                    }
                    addView(urlTv)
                    addView(timeTv)
                }
                textCol.addView(titleTv)
                textCol.addView(subRow)

                val btnDelete = ImageButton(activity).apply {
                    id = View.generateViewId()
                    setImageResource(R.drawable.ic_close)
                    setColorFilter(subTextColor)
                    setBackgroundResource(getSelectableRes(activity))
                    layoutParams = LinearLayout.LayoutParams(dpToPx(activity, 32), dpToPx(activity, 32))
                }

                row.addView(icon)
                row.addView(textCol)
                row.addView(btnDelete)

                return ItemViewHolder(row, titleTv, subRow.getChildAt(0) as TextView, subRow.getChildAt(1) as TextView, btnDelete)
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            if (holder is HeaderViewHolder) {
                holder.textView.text = items[position] as String
            } else if (holder is ItemViewHolder) {
                val item = items[position] as HistoryItem
                holder.titleTv.text = item.title
                val domain = try { Uri.parse(item.url).host ?: item.url } catch (e: Exception) { item.url }
                holder.urlTv.text = domain
                holder.timeTv.text = timeFormat.format(Date(item.timestamp))

                holder.itemView.setOnClickListener { onItemClick(item) }
                holder.itemView.setOnLongClickListener {
                    showHistoryItemOptions(activity, item, { onItemDelete(item) }, { onItemClick(item) })
                    true
                }
                holder.btnDelete.setOnClickListener {
                    activity.triggerStrongHaptic(it)
                    onItemDelete(item)
                }
            }
        }

        override fun getItemCount(): Int = items.size
        class HeaderViewHolder(val textView: TextView) : RecyclerView.ViewHolder(textView)
        class ItemViewHolder(
            val view: View,
            val titleTv: TextView,
            val urlTv: TextView,
            val timeTv: TextView,
            val btnDelete: ImageButton
        ) : RecyclerView.ViewHolder(view)
    }

    private class HistorySearchAdapter(
        private val activity: MainActivity,
        private val list: List<HistoryItem>,
        private val onItemClick: (HistoryItem) -> Unit,
        private val onItemDelete: (HistoryItem) -> Unit,
        private val isDark: Boolean,
        private val textColor: Int,
        private val subTextColor: Int,
        private val cardBgColor: Int
    ) : RecyclerView.Adapter<HistorySearchAdapter.SearchViewHolder>() {

        private val timeFormat = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())

        class SearchViewHolder(
            val view: View,
            val titleTv: TextView,
            val urlTv: TextView,
            val timeTv: TextView,
            val btnDelete: ImageButton
        ) : RecyclerView.ViewHolder(view)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SearchViewHolder {
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(activity, 62))
                setPadding(dpToPx(activity, 16), 0, dpToPx(activity, 12), 0)
                setBackgroundResource(getSelectableRes(activity))
            }

            val icon = ImageView(activity).apply {
                layoutParams = LinearLayout.LayoutParams(dpToPx(activity, 24), dpToPx(activity, 24)).apply {
                    marginEnd = dpToPx(activity, 14)
                }
                setImageResource(R.drawable.ic_globe)
                setColorFilter(subTextColor)
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
                val urlTv = TextView(activity).apply {
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
                addView(urlTv)
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

            row.addView(icon)
            row.addView(textCol)
            row.addView(btnDelete)

            return SearchViewHolder(row, titleTv, subRow.getChildAt(0) as TextView, subRow.getChildAt(1) as TextView, btnDelete)
        }

        override fun onBindViewHolder(holder: SearchViewHolder, position: Int) {
            val item = list[position]
            holder.titleTv.text = item.title
            holder.urlTv.text = item.url
            holder.timeTv.text = timeFormat.format(Date(item.timestamp))

            holder.itemView.setOnClickListener { onItemClick(item) }
            holder.itemView.setOnLongClickListener {
                showHistoryItemOptions(activity, item, { onItemDelete(item) }, { onItemClick(item) })
                true
            }
            holder.btnDelete.setOnClickListener {
                activity.triggerStrongHaptic(it)
                onItemDelete(item)
            }
        }

        override fun getItemCount(): Int = list.size
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
