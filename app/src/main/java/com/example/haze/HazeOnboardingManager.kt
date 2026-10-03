package com.example.haze

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*

object HazeOnboardingManager {

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

    fun showOnboardingIfNeeded(activity: MainActivity, container: ViewGroup): View? {
        if (HazeAccountManager.hasCompletedOnboarding(activity)) {
            return null
        }

        val prefs = activity.getSharedPreferences("HazePrefs", Context.MODE_PRIVATE)
        val isDark = prefs.getBoolean("is_dark", false)

        val bgColor = if (isDark) Color.parseColor("#121212") else Color.parseColor("#FAFAFA")
        val textColor = if (isDark) Color.WHITE else Color.parseColor("#202124")
        val subTextColor = if (isDark) Color.parseColor("#9AA0A6") else Color.parseColor("#5F6368")
        val borderColor = if (isDark) Color.parseColor("#383838") else Color.parseColor("#DADCE0")

        val root = FrameLayout(activity).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(bgColor)
            isClickable = true
            isFocusable = true
            elevation = dpToPx(activity, 40).toFloat()
        }

        val contentLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER
            }
            setPadding(dpToPx(activity, 32), dpToPx(activity, 32), dpToPx(activity, 32), dpToPx(activity, 32))
        }

        // --- 1. App Logo ---
        val logoView = ImageView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(dpToPx(activity, 140), dpToPx(activity, 90)).apply {
                bottomMargin = dpToPx(activity, 24)
            }
            setImageResource(R.drawable.ic_haze_logo)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        contentLayout.addView(logoView)

        // --- 2. Welcome Headline ---
        val tvWelcome = TextView(activity).apply {
            text = "Welcome to Haze"
            textSize = 26f
            setTypeface(null, Typeface.BOLD)
            setTextColor(textColor)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dpToPx(activity, 48)
            }
        }
        contentLayout.addView(tvWelcome)

        // --- 3. Continue with Google Button ---
        val btnGoogle = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx(activity, 52)
            ).apply {
                bottomMargin = dpToPx(activity, 16)
            }
            background = GradientDrawable().apply {
                cornerRadius = dpToPx(activity, 26).toFloat()
                setColor(if (isDark) Color.parseColor("#1F1F1F") else Color.WHITE)
                setStroke(dpToPx(activity, 1), borderColor)
            }
            isClickable = true
            isFocusable = true
            setPadding(dpToPx(activity, 16), 0, dpToPx(activity, 16), 0)
        }

        val ivGoogleLogo = ImageView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(dpToPx(activity, 22), dpToPx(activity, 22)).apply {
                marginEnd = dpToPx(activity, 12)
            }
            setImageResource(R.drawable.ic_google_logo)
        }

        val tvGoogle = TextView(activity).apply {
            text = "Continue with Google"
            textSize = 15f
            setTypeface(null, Typeface.BOLD)
            setTextColor(textColor)
        }

        btnGoogle.addView(ivGoogleLogo)
        btnGoogle.addView(tvGoogle)
        contentLayout.addView(btnGoogle)

        // --- 4. Continue as Guest Button ---
        val btnGuest = Button(activity, null, android.R.attr.borderlessButtonStyle).apply {
            text = "Continue as Guest"
            textSize = 14.5f
            setTypeface(null, Typeface.BOLD)
            setTextColor(subTextColor)
            setBackgroundResource(getSelectableRes(activity))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx(activity, 48)
            )
        }
        contentLayout.addView(btnGuest)

        root.addView(contentLayout)

        // --- Click Handlers ---
        btnGoogle.setOnClickListener {
            activity.triggerStrongHaptic(it)
            btnGoogle.isEnabled = false
            btnGuest.isEnabled = false
            tvGoogle.text = "Signing in..."

            activity.launchGoogleSignIn(
                onSuccess = { user ->
                    HazeAccountManager.setOnboardingCompleted(activity, true)
                    HazeSyncManager.performSync(activity)
                    dismissOnboarding(container, root)
                    activity.triggerStrongHaptic(isHeavy = true)
                    Toast.makeText(activity, "Welcome, ${user.givenName}!", Toast.LENGTH_SHORT).show()
                },
                onError = { errorMsg ->
                    btnGoogle.isEnabled = true
                    btnGuest.isEnabled = true
                    tvGoogle.text = "Continue with Google"
                    Toast.makeText(activity, errorMsg, Toast.LENGTH_SHORT).show()
                }
            )
        }

        btnGuest.setOnClickListener {
            activity.triggerStrongHaptic(it)
            HazeAccountManager.setOnboardingCompleted(activity, true)
            dismissOnboarding(container, root)
            Toast.makeText(activity, "Continuing as Guest", Toast.LENGTH_SHORT).show()
        }

        container.addView(root)
        return root
    }

    private fun dismissOnboarding(container: ViewGroup, root: View) {
        root.animate()
            .alpha(0f)
            .translationY(dpToPx(root.context, 40).toFloat())
            .setDuration(300)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    try {
                        container.removeView(root)
                    } catch (e: Exception) {
                    }
                }
            })
    }
}
