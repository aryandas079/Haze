package com.example.haze

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
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
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.*
import com.google.android.gms.auth.api.signin.GoogleSignIn

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

        val bgColor = if (isDark) Color.parseColor("#121214") else Color.parseColor("#FFFFFF")
        val cardBgColor = if (isDark) Color.parseColor("#1E1F24") else Color.parseColor("#F6F8FC")
        val textColor = if (isDark) Color.parseColor("#E8EAED") else Color.parseColor("#1F1F1F")
        val subTextColor = if (isDark) Color.parseColor("#9AA0A6") else Color.parseColor("#5F6368")
        val subtleTextColor = if (isDark) Color.parseColor("#70757A") else Color.parseColor("#80868B")
        val brandColor = Color.parseColor("#0B57D0")
        val brandPressColor = Color.parseColor("#0842A0")

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

        // Main vertical content container
        val contentLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setPadding(
                dpToPx(activity, 28),
                dpToPx(activity, 48),
                dpToPx(activity, 28),
                dpToPx(activity, 32)
            )
        }

        // --- Top Flexible Space (Pushes hero to upper-middle) ---
        val topSpace = Space(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1.1f
            )
        }
        contentLayout.addView(topSpace)

        // --- 1. HEADLINE ("Make Haze your own") ---
        val tvWelcome = TextView(activity).apply {
            text = "Make Haze your own"
            textSize = 28f
            setTypeface(Typeface.create("sans-serif", Typeface.BOLD))
            setTextColor(textColor)
            gravity = Gravity.CENTER
            letterSpacing = -0.015f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dpToPx(activity, 40)
            }
        }
        contentLayout.addView(tvWelcome)

        // --- Middle Flexible Space ---
        val middleSpace = Space(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1.4f
            )
        }
        contentLayout.addView(middleSpace)

        // --- 2. PRIMARY ACTION ("Continue with Google") ---
        val btnContinue = FrameLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx(activity, 52)
            ).apply {
                bottomMargin = dpToPx(activity, 14)
            }
            background = GradientDrawable().apply {
                cornerRadius = dpToPx(activity, 26).toFloat()
                setColor(brandColor)
            }
            isClickable = true
            isFocusable = true
        }

        val tvContinue = TextView(activity).apply {
            text = "Continue with Google"
            textSize = 15.5f
            setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        }
        btnContinue.addView(tvContinue)
        contentLayout.addView(btnContinue)

        // --- 3. SECONDARY ACTION ("Skip") ---
        val btnSkip = TextView(activity).apply {
            text = "Skip"
            textSize = 15f
            setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
            setTextColor(brandColor)
            gravity = Gravity.CENTER
            setBackgroundResource(getSelectableRes(activity))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dpToPx(activity, 48)
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dpToPx(activity, 24)
            }
            val pad = dpToPx(activity, 24)
            setPadding(pad, 0, pad, 0)
            isClickable = true
            isFocusable = true
        }
        contentLayout.addView(btnSkip)

        root.addView(contentLayout)

        // Primary Action
        btnContinue.setOnClickListener {
            activity.triggerStrongHaptic(it)
            btnContinue.isEnabled = false
            btnSkip.isEnabled = false
            tvContinue.text = "Connecting..."

            activity.launchGoogleSignIn(
                onSuccess = { user ->
                    HazeAccountManager.setOnboardingCompleted(activity, true)
                    HazeSyncManager.performSync(activity)
                    dismissOnboarding(container, root)
                    activity.triggerStrongHaptic(isHeavy = true)
                    Toast.makeText(activity, "Welcome to Haze, ${user.givenName}!", Toast.LENGTH_SHORT).show()
                    activity.checkAndPromptGoogleWebLogin()
                },
                onError = { errorMsg ->
                    btnContinue.isEnabled = true
                    btnSkip.isEnabled = true
                    tvContinue.text = "Continue with Google"
                    Toast.makeText(activity, errorMsg, Toast.LENGTH_SHORT).show()
                }
            )
        }

        // Secondary Action ("Skip")
        btnSkip.setOnClickListener {
            activity.triggerStrongHaptic(it)
            HazeAccountManager.setOnboardingCompleted(activity, true)
            dismissOnboarding(container, root)
        }

        container.addView(root)
        return root
    }

    private fun dismissOnboarding(container: ViewGroup, root: View) {
        root.animate()
            .alpha(0f)
            .translationY(dpToPx(root.context, 32).toFloat())
            .setDuration(320)
            .setInterpolator(MainActivity.BUTTER_INTERPOLATOR)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    try {
                        container.removeView(root)
                    } catch (_: Exception) {
                    }
                }
            })
    }
}
