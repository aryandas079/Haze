package com.example.haze

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.core.widget.NestedScrollView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog

object HazeProfileDialogManager {

    private fun dpToPx(context: Context, dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            context.resources.displayMetrics
        ).toInt()
    }

    private fun getSelectableRes(context: Context): Int {
        val outValue = TypedValue()
        context.theme.resolveAttribute(android.R.attr.selectableItemBackground, outValue, true)
        return outValue.resourceId
    }

    fun showProfilePanel(activity: MainActivity) {
        activity.triggerStrongHaptic(isHeavy = false)
        if (HazeAccountManager.isSignedIn(activity)) {
            showSignedInProfilePanel(activity)
        } else {
            showSignedOutProfilePanel(activity)
        }
    }

    private fun createMenuItem(
        context: Context,
        iconRes: Int,
        title: String,
        subtitle: String? = null,
        endIconRes: Int? = null,
        textColor: Int,
        subTextColor: Int,
        onClick: () -> Unit
    ): LinearLayout {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpToPx(context, 16), dpToPx(context, 14), dpToPx(context, 16), dpToPx(context, 14))
            setBackgroundResource(getSelectableRes(context))
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

        val icon = ImageView(context).apply {
            layoutParams = LinearLayout.LayoutParams(dpToPx(context, 20), dpToPx(context, 20)).apply {
                marginEnd = dpToPx(context, 16)
            }
            setImageResource(iconRes)
            setColorFilter(subTextColor)
        }
        row.addView(icon)

        val textCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val tvTitle = TextView(context).apply {
            text = title
            textSize = 14.5f
            setTextColor(textColor)
            setTypeface(null, Typeface.NORMAL)
        }
        textCol.addView(tvTitle)

        if (subtitle != null) {
            val tvSub = TextView(context).apply {
                text = subtitle
                textSize = 12f
                setTextColor(subTextColor)
                setPadding(0, dpToPx(context, 2), 0, 0)
            }
            textCol.addView(tvSub)
        }
        row.addView(textCol)

        if (endIconRes != null) {
            val endIcon = ImageView(context).apply {
                layoutParams = LinearLayout.LayoutParams(dpToPx(context, 16), dpToPx(context, 16)).apply {
                    marginStart = dpToPx(context, 8)
                }
                setImageResource(endIconRes)
                setColorFilter(subTextColor)
            }
            row.addView(endIcon)
        }

        return row
    }

    // =========================================================================
    // 1. SIGNED-IN PROFILE PANEL (Chrome & Edge Inspired Design)
    // =========================================================================
    private fun showSignedInProfilePanel(activity: MainActivity) {
        val user = HazeAccountManager.getUser(activity) ?: run {
            showSignedOutProfilePanel(activity)
            return
        }

        val prefs = activity.getSharedPreferences("HazePrefs", Context.MODE_PRIVATE)
        val isDark = prefs.getBoolean("is_dark", false) || activity.isPrivateMode

        val accentColorHex = prefs.getString("accent_color", "#0A56D0") ?: "#0A56D0"
        val accentColor = Color.parseColor(accentColorHex)

        val bgColor = if (isDark) Color.parseColor("#1C1C1C") else Color.WHITE
        val headerCardColor = if (isDark) Color.parseColor("#282828") else Color.parseColor("#EEF2FB")
        val textColor = if (isDark) Color.WHITE else Color.parseColor("#202124")
        val subTextColor = if (isDark) Color.parseColor("#9AA0A6") else Color.parseColor("#5F6368")
        val dividerColor = if (isDark) Color.parseColor("#333333") else Color.parseColor("#E0E0E0")

        val dialog = BottomSheetDialog(activity)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        dialog.setOnShowListener {
            val sheetResId = dialog.context.resources.getIdentifier("design_bottom_sheet", "id", dialog.context.packageName)
            val bottomSheet = if (sheetResId != 0) dialog.findViewById<View>(sheetResId) else null
            if (bottomSheet != null) {
                val behavior = BottomSheetBehavior.from(bottomSheet)
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
            }
        }

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadii = floatArrayOf(
                    dpToPx(activity, 28).toFloat(), dpToPx(activity, 28).toFloat(),
                    dpToPx(activity, 28).toFloat(), dpToPx(activity, 28).toFloat(),
                    0f, 0f, 0f, 0f
                )
                setColor(bgColor)
            }
            setPadding(dpToPx(activity, 16), dpToPx(activity, 8), dpToPx(activity, 16), dpToPx(activity, 24))
        }

        // --- Drag Handle ---
        val handleWrapper = FrameLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(activity, 18)).apply {
                bottomMargin = dpToPx(activity, 8)
            }
        }
        val handlePill = View(activity).apply {
            layoutParams = FrameLayout.LayoutParams(dpToPx(activity, 36), dpToPx(activity, 4)).apply {
                gravity = Gravity.CENTER
            }
            background = GradientDrawable().apply {
                cornerRadius = dpToPx(activity, 2).toFloat()
                setColor(if (isDark) Color.parseColor("#444444") else Color.parseColor("#D0D0D0"))
            }
        }
        handleWrapper.addView(handlePill)
        root.addView(handleWrapper)

        val scrollView = NestedScrollView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            isVerticalScrollBarEnabled = false
        }

        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
        }
        scrollView.addView(content)
        root.addView(scrollView)

        // --- 1. Top Header Card (Chrome / Edge Inspired) ---
        val headerCard = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                cornerRadius = dpToPx(activity, 20).toFloat()
                setColor(headerCardColor)
            }
            setPadding(dpToPx(activity, 20), dpToPx(activity, 24), dpToPx(activity, 20), dpToPx(activity, 20))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dpToPx(activity, 12)
            }
        }

        val ivAvatar = ImageView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(dpToPx(activity, 68), dpToPx(activity, 68)).apply {
                bottomMargin = dpToPx(activity, 12)
            }
            scaleType = ImageView.ScaleType.CENTER_CROP
        }
        HazeProfileImageHelper.loadAvatar(
            activity,
            user.photoUrl,
            user.givenName,
            ivAvatar,
            dpToPx(activity, 68)
        )

        val tvName = TextView(activity).apply {
            text = user.displayName
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setTextColor(textColor)
            gravity = Gravity.CENTER
        }

        val tvEmail = TextView(activity).apply {
            text = user.email
            textSize = 13f
            setTextColor(subTextColor)
            gravity = Gravity.CENTER
            setPadding(0, dpToPx(activity, 2), 0, 0)
        }

        headerCard.addView(ivAvatar)
        headerCard.addView(tvName)
        headerCard.addView(tvEmail)
        content.addView(headerCard)

        // --- 2. Primary Menu List ---
        val primaryList = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        // Passwords and Autofill
        primaryList.addView(
            createMenuItem(activity, R.drawable.ic_key, "Passwords and autofill", textColor = textColor, subTextColor = subTextColor) {
                activity.triggerStrongHaptic()
                dialog.dismiss()
                activity.addNewTab("haze://settings")
            }
        )

        // Manage your Google Account
        primaryList.addView(
            createMenuItem(activity, R.drawable.ic_person, "Manage your Google Account", textColor = textColor, subTextColor = subTextColor) {
                activity.triggerStrongHaptic()
                dialog.dismiss()
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://myaccount.google.com"))
                activity.startActivity(intent)
            }
        )

        // Customize profile
        primaryList.addView(
            createMenuItem(activity, R.drawable.ic_palette, "Customize profile", textColor = textColor, subTextColor = subTextColor) {
                activity.triggerStrongHaptic()
                dialog.dismiss()
                activity.intent.putExtra("open_personalize", true)
                activity.recreate()
            }
        )

        // Sync is on
        primaryList.addView(
            createMenuItem(activity, R.drawable.ic_sync, "Sync is on", HazeSyncManager.getLastSyncFormatted(activity), textColor = textColor, subTextColor = subTextColor) {
                activity.triggerStrongHaptic()
                HazeSyncManager.performSync(activity)
                Toast.makeText(activity, "Syncing data now...", Toast.LENGTH_SHORT).show()
            }
        )

        // Sign out of Haze
        primaryList.addView(
            createMenuItem(activity, R.drawable.ic_logout, "Sign out of Haze", textColor = textColor, subTextColor = subTextColor) {
                activity.triggerStrongHaptic()
                dialog.dismiss()
                HazeAccountManager.clearUser(activity)
                activity.updateProfileButton()
                Toast.makeText(activity, "Signed out", Toast.LENGTH_SHORT).show()
            }
        )

        content.addView(primaryList)

        // --- 3. Divider Line ---
        val divider = View(activity).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(activity, 1)).apply {
                topMargin = dpToPx(activity, 8)
                bottomMargin = dpToPx(activity, 8)
            }
            setBackgroundColor(dividerColor)
        }
        content.addView(divider)

        // --- 4. Secondary Menu List ---
        val secondaryList = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }

        // Set up a new profile
        secondaryList.addView(
            createMenuItem(activity, R.drawable.ic_add, "Set up a new profile", textColor = textColor, subTextColor = subTextColor) {
                activity.triggerStrongHaptic()
                dialog.dismiss()
                Toast.makeText(activity, "Add profile feature coming soon", Toast.LENGTH_SHORT).show()
            }
        )

        // Browse as guest
        secondaryList.addView(
            createMenuItem(activity, R.drawable.ic_private, "Browse as guest", textColor = textColor, subTextColor = subTextColor) {
                activity.triggerStrongHaptic()
                dialog.dismiss()
                activity.addNewTab("haze://home")
            }
        )

        content.addView(secondaryList)

        dialog.setContentView(root)
        dialog.show()
    }

    // =========================================================================
    // 2. SIGNED-OUT PROFILE PANEL (Matching Clean Design)
    // =========================================================================
    private fun showSignedOutProfilePanel(activity: MainActivity) {
        val prefs = activity.getSharedPreferences("HazePrefs", Context.MODE_PRIVATE)
        val isDark = prefs.getBoolean("is_dark", false) || activity.isPrivateMode

        val accentColorHex = prefs.getString("accent_color", "#0A56D0") ?: "#0A56D0"
        val accentColor = Color.parseColor(accentColorHex)

        val bgColor = if (isDark) Color.parseColor("#1C1C1C") else Color.WHITE
        val headerCardColor = if (isDark) Color.parseColor("#282828") else Color.parseColor("#EEF2FB")
        val textColor = if (isDark) Color.WHITE else Color.parseColor("#202124")
        val subTextColor = if (isDark) Color.parseColor("#9AA0A6") else Color.parseColor("#5F6368")

        val dialog = BottomSheetDialog(activity)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                cornerRadii = floatArrayOf(
                    dpToPx(activity, 28).toFloat(), dpToPx(activity, 28).toFloat(),
                    dpToPx(activity, 28).toFloat(), dpToPx(activity, 28).toFloat(),
                    0f, 0f, 0f, 0f
                )
                setColor(bgColor)
            }
            setPadding(dpToPx(activity, 16), dpToPx(activity, 8), dpToPx(activity, 16), dpToPx(activity, 24))
        }

        // --- Drag Handle ---
        val handleWrapper = FrameLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpToPx(activity, 18)).apply {
                bottomMargin = dpToPx(activity, 8)
            }
        }
        val handlePill = View(activity).apply {
            layoutParams = FrameLayout.LayoutParams(dpToPx(activity, 36), dpToPx(activity, 4)).apply {
                gravity = Gravity.CENTER
            }
            background = GradientDrawable().apply {
                cornerRadius = dpToPx(activity, 2).toFloat()
                setColor(if (isDark) Color.parseColor("#444444") else Color.parseColor("#D0D0D0"))
            }
        }
        handleWrapper.addView(handlePill)
        root.addView(handleWrapper)

        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        // --- Header Card ---
        val headerCard = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                cornerRadius = dpToPx(activity, 20).toFloat()
                setColor(headerCardColor)
            }
            setPadding(dpToPx(activity, 20), dpToPx(activity, 24), dpToPx(activity, 20), dpToPx(activity, 20))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dpToPx(activity, 16)
            }
        }

        val avatarBox = FrameLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(dpToPx(activity, 64), dpToPx(activity, 64)).apply {
                bottomMargin = dpToPx(activity, 12)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (isDark) Color.parseColor("#383838") else Color.WHITE)
            }
        }
        val ivPerson = ImageView(activity).apply {
            layoutParams = FrameLayout.LayoutParams(dpToPx(activity, 32), dpToPx(activity, 32)).apply {
                gravity = Gravity.CENTER
            }
            setImageResource(R.drawable.ic_person)
            setColorFilter(subTextColor)
        }
        avatarBox.addView(ivPerson)

        val tvTitle = TextView(activity).apply {
            text = "Sign in to Haze"
            textSize = 18f
            setTypeface(null, Typeface.BOLD)
            setTextColor(textColor)
            gravity = Gravity.CENTER
        }

        val tvSub = TextView(activity).apply {
            text = "Sync passwords, bookmarks & settings across your devices"
            textSize = 12.5f
            setTextColor(subTextColor)
            gravity = Gravity.CENTER
            setPadding(0, dpToPx(activity, 4), 0, 0)
        }

        headerCard.addView(avatarBox)
        headerCard.addView(tvTitle)
        headerCard.addView(tvSub)
        content.addView(headerCard)

        // Continue with Google Button
        val btnGoogle = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                cornerRadius = dpToPx(activity, 24).toFloat()
                setColor(accentColor)
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx(activity, 48)
            ).apply {
                bottomMargin = dpToPx(activity, 12)
            }
            isClickable = true
            isFocusable = true
        }

        val ivG = ImageView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(dpToPx(activity, 20), dpToPx(activity, 20)).apply {
                marginEnd = dpToPx(activity, 10)
            }
            setImageResource(R.drawable.ic_google_logo)
        }
        val tvG = TextView(activity).apply {
            text = "Continue with Google"
            textSize = 14.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.WHITE)
        }
        btnGoogle.addView(ivG)
        btnGoogle.addView(tvG)
        content.addView(btnGoogle)

        // Browse as Guest
        val guestRow = createMenuItem(activity, R.drawable.ic_private, "Browse as guest", textColor = textColor, subTextColor = subTextColor) {
            activity.triggerStrongHaptic()
            dialog.dismiss()
        }
        content.addView(guestRow)

        btnGoogle.setOnClickListener {
            activity.triggerStrongHaptic(it)
            btnGoogle.isEnabled = false
            tvG.text = "Connecting..."

            activity.launchGoogleSignIn(
                onSuccess = { user ->
                    dialog.dismiss()
                    activity.triggerStrongHaptic(isHeavy = true)
                    HazeSyncManager.performSync(activity)
                    Toast.makeText(activity, "Signed in as ${user.displayName}", Toast.LENGTH_SHORT).show()
                },
                onError = { errorMsg ->
                    btnGoogle.isEnabled = true
                    tvG.text = "Continue with Google"
                    Toast.makeText(activity, errorMsg, Toast.LENGTH_SHORT).show()
                }
            )
        }

        root.addView(content)
        dialog.setContentView(root)
        dialog.show()
    }
}
