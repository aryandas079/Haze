package com.example.haze

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.card.MaterialCardView

object HazeTabGroupDialogManager {

    val GROUP_COLORS = listOf(
        Color.parseColor("#5F6368"), // Charcoal / Grey
        Color.parseColor("#1A73E8"), // Blue
        Color.parseColor("#D93025"), // Red
        Color.parseColor("#F29900"), // Yellow / Orange
        Color.parseColor("#1E8E3E"), // Green
        Color.parseColor("#E52592"), // Pink / Magenta
        Color.parseColor("#7050E0"), // Purple / Violet
        Color.parseColor("#007B83"), // Teal / Cyan
        Color.parseColor("#E8710A")  // Coral / Tangerine
    )

    private fun dpToPx(context: Context, dp: Float): Int =
        (dp * context.resources.displayMetrics.density + 0.5f).toInt()

    /**
     * Shows the "New tab group" / "Edit tab group" modal dialog matching Image 2.
     */
    fun showNewTabGroupDialog(
        context: Context,
        initialTitle: String = "2 tabs",
        initialColor: Int = GROUP_COLORS[1], // Blue by default
        isEditing: Boolean = false,
        onDone: (title: String, color: Int) -> Unit
    ) {
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_new_tab_group, null)
        val tvHeading = dialogView.findViewById<TextView>(R.id.tvNewGroupHeading)
        val etTitle = dialogView.findViewById<EditText>(R.id.etGroupTitleInput)
        val row1 = dialogView.findViewById<LinearLayout>(R.id.layoutColorPaletteRow1)
        val row2 = dialogView.findViewById<LinearLayout>(R.id.layoutColorPaletteRow2)
        val btnDone = dialogView.findViewById<TextView>(R.id.btnGroupDone)
        val btnCancel = dialogView.findViewById<TextView>(R.id.btnGroupCancel)

        if (isEditing) {
            tvHeading.text = "Edit tab group"
        } else {
            tvHeading.text = "New tab group"
        }

        etTitle.setText(initialTitle)
        etTitle.setSelection(etTitle.text.length)

        var selectedColor = initialColor
        val colorViews = mutableListOf<Pair<Int, FrameLayout>>()

        fun createColorSwatchView(color: Int): FrameLayout {
            val containerSize = dpToPx(context, 42f)
            val dotSize = dpToPx(context, 32f)

            val container = FrameLayout(context).apply {
                layoutParams = LinearLayout.LayoutParams(containerSize, containerSize).apply {
                    val margin = dpToPx(context, 6f)
                    setMargins(margin, 0, margin, 0)
                }
            }

            val outerRing = View(context).apply {
                layoutParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            }

            val innerDot = View(context).apply {
                layoutParams = FrameLayout.LayoutParams(dotSize, dotSize, Gravity.CENTER)
            }

            container.addView(outerRing)
            container.addView(innerDot)
            return container
        }

        fun refreshColorPaletteUI() {
            for ((color, container) in colorViews) {
                val outerRing = container.getChildAt(0)
                val innerDot = container.getChildAt(1)
                val isSelected = (color == selectedColor)

                // Inner Dot
                val dotDrawable = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(color)
                }
                innerDot.background = dotDrawable

                if (isSelected) {
                    val strokeColor = if (color == Color.parseColor("#5F6368")) Color.parseColor("#3C4043") else color
                    val ringDrawable = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(Color.TRANSPARENT)
                        setStroke(dpToPx(context, 2.5f), strokeColor)
                    }
                    outerRing.background = ringDrawable
                    outerRing.visibility = View.VISIBLE
                } else {
                    outerRing.visibility = View.GONE
                }
            }
        }

        // Row 1: 5 colors
        for (i in 0 until 5.coerceAtMost(GROUP_COLORS.size)) {
            val color = GROUP_COLORS[i]
            val swatch = createColorSwatchView(color)
            swatch.setOnClickListener {
                selectedColor = color
                refreshColorPaletteUI()
            }
            colorViews.add(color to swatch)
            row1.addView(swatch)
        }

        // Row 2: remaining 4 colors
        for (i in 5 until GROUP_COLORS.size) {
            val color = GROUP_COLORS[i]
            val swatch = createColorSwatchView(color)
            swatch.setOnClickListener {
                selectedColor = color
                refreshColorPaletteUI()
            }
            colorViews.add(color to swatch)
            row2.addView(swatch)
        }

        refreshColorPaletteUI()

        val alertDialog = AlertDialog.Builder(context)
            .setView(dialogView)
            .create()

        alertDialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        alertDialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)

        btnDone.setOnClickListener {
            val title = etTitle.text.toString().trim().ifEmpty { initialTitle }
            onDone(title, selectedColor)
            alertDialog.dismiss()
        }

        btnCancel.setOnClickListener {
            alertDialog.dismiss()
        }

        alertDialog.show()

        val width = (context.resources.displayMetrics.widthPixels * 0.92f).toInt()
        alertDialog.window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    /**
     * Shows the Tab Group Details Dialog when clicking on a Tab Group Card (Image 1).
     * Displays all member tabs in a clean 2-column grid, allowing user to open, close, or add tabs.
     */
    fun showTabGroupDetailDialog(
        activity: MainActivity,
        group: MainActivity.TabGroup,
        groupTabs: List<MainActivity.Tab>,
        isPrivate: Boolean,
        onTabSelected: (MainActivity.Tab) -> Unit,
        onTabClosed: (MainActivity.Tab) -> Unit,
        onNewTabInGroup: () -> Unit,
        onGroupModified: () -> Unit
    ) {
        val dialog = Dialog(activity, android.R.style.Theme_DeviceDefault_Light_NoActionBar_Fullscreen)
        val dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_tab_group_detail, null)
        dialog.setContentView(dialogView)

        val btnBack = dialogView.findViewById<ImageButton>(R.id.btnGroupDetailBack)
        val vColorDot = dialogView.findViewById<View>(R.id.vGroupDetailColorIndicator)
        val tvTitle = dialogView.findViewById<TextView>(R.id.tvGroupDetailTitle)
        val btnNewTab = dialogView.findViewById<ImageButton>(R.id.btnGroupDetailNewTab)
        val btnMenu = dialogView.findViewById<ImageButton>(R.id.btnGroupDetailMenu)
        val rvTabs = dialogView.findViewById<RecyclerView>(R.id.rvGroupMemberTabs)

        // Setup Header Colors
        vColorDot.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(group.color)
        }
        tvTitle.text = group.name

        btnBack.setOnClickListener {
            dialog.dismiss()
        }

        btnNewTab.setOnClickListener {
            dialog.dismiss()
            onNewTabInGroup()
        }

        btnMenu.setOnClickListener { anchorView ->
            val popup = androidx.appcompat.widget.PopupMenu(activity, anchorView)
            popup.menu.add("New tab in group")
            popup.menu.add("Edit group name and color")
            popup.menu.add("Ungroup tabs")
            popup.menu.add("Close group")

            popup.setOnMenuItemClickListener { item ->
                when (item.title) {
                    "New tab in group" -> {
                        dialog.dismiss()
                        onNewTabInGroup()
                        true
                    }
                    "Edit group name and color" -> {
                        showNewTabGroupDialog(
                            context = activity,
                            initialTitle = group.name,
                            initialColor = group.color,
                            isEditing = true
                        ) { newTitle, newColor ->
                            group.name = newTitle
                            group.color = newColor
                            tvTitle.text = newTitle
                            vColorDot.background = GradientDrawable().apply {
                                shape = GradientDrawable.OVAL
                                setColor(newColor)
                            }
                            onGroupModified()
                        }
                        true
                    }
                    "Ungroup tabs" -> {
                        for (t in groupTabs) {
                            t.group = null
                        }
                        dialog.dismiss()
                        onGroupModified()
                        true
                    }
                    "Close group" -> {
                        for (t in groupTabs.toList()) {
                            onTabClosed(t)
                        }
                        dialog.dismiss()
                        onGroupModified()
                        true
                    }
                    else -> false
                }
            }
            popup.show()
        }

        // Setup Member Tabs Adapter
        val memberList = groupTabs.toMutableList()
        rvTabs.layoutManager = GridLayoutManager(activity, 2)

        class MemberViewHolder(v: View) : RecyclerView.ViewHolder(v) {
            val cardTabGrid: MaterialCardView? = v.findViewById(R.id.cardTabGrid)
            val gridTabTitle: TextView? = v.findViewById(R.id.gridTabTitle)
            val gridTabIcon: ImageView? = v.findViewById(R.id.gridTabIcon)
            val gridTabClose: ImageButton? = v.findViewById(R.id.gridTabClose)
            val gridTabThumbnail: ImageView? = v.findViewById(R.id.gridTabThumbnail)
            val gridGroupIndicator: View? = v.findViewById(R.id.gridGroupIndicator)
        }

        val adapter = object : RecyclerView.Adapter<MemberViewHolder>() {
            override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MemberViewHolder {
                val view = LayoutInflater.from(activity).inflate(R.layout.item_tab_grid, parent, false)
                return MemberViewHolder(view)
            }

            override fun getItemCount(): Int = memberList.size

            override fun onBindViewHolder(holder: MemberViewHolder, position: Int) {
                val tab = memberList[position]
                holder.gridTabTitle?.text = tab.title
                holder.gridGroupIndicator?.visibility = View.VISIBLE
                holder.gridGroupIndicator?.setBackgroundColor(group.color)

                if (activity.isHomeTab(tab)) {
                    holder.gridTabIcon?.setImageResource(R.drawable.ic_haze_logo)
                } else if (tab.favicon != null) {
                    holder.gridTabIcon?.setImageBitmap(tab.favicon)
                } else {
                    holder.gridTabIcon?.setImageResource(R.drawable.ic_globe)
                }

                tab.browserTab.captureThumbnail { bmp ->
                    if (bmp != null) holder.gridTabThumbnail?.setImageBitmap(bmp)
                    else holder.gridTabThumbnail?.setImageResource(R.drawable.haze_logo)
                }

                holder.gridTabClose?.setOnClickListener {
                    val pos = holder.bindingAdapterPosition
                    if (pos != -1 && pos in memberList.indices) {
                        val toClose = memberList.removeAt(pos)
                        notifyItemRemoved(pos)
                        onTabClosed(toClose)
                        if (memberList.isEmpty()) {
                            dialog.dismiss()
                            onGroupModified()
                        }
                    }
                }

                holder.itemView.setOnClickListener {
                    dialog.dismiss()
                    onTabSelected(tab)
                }
            }
        }

        rvTabs.adapter = adapter
        dialog.show()
    }
}
