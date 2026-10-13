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

        // --- 1. HERO LOGO AREA ---
        val logoContainer = FrameLayout(activity).apply {
            val haloSize = dpToPx(activity, 112)
            layoutParams = LinearLayout.LayoutParams(haloSize, haloSize).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dpToPx(activity, 28)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (isDark) Color.parseColor("#1E212A") else Color.parseColor("#EEF4FE"))
            }
        }

        val logoView = ImageView(activity).apply {
            val logoSize = dpToPx(activity, 64)
            layoutParams = FrameLayout.LayoutParams(
                logoSize,
                logoSize,
                Gravity.CENTER
            )
            setImageResource(R.drawable.ic_haze_logo)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        logoContainer.addView(logoView)
        contentLayout.addView(logoContainer)

        // --- 2. HEADLINE & SUBHEAD ---
        val tvWelcome = TextView(activity).apply {
            text = "Make Haze your own"
            textSize = 24f
            setTypeface(Typeface.create("sans-serif", Typeface.BOLD))
            setTextColor(textColor)
            gravity = Gravity.CENTER
            letterSpacing = -0.015f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dpToPx(activity, 10)
            }
        }
        contentLayout.addView(tvWelcome)

        val tvSub = TextView(activity).apply {
            text = "Sign in to sync your tabs, bookmarks, history, and passwords seamlessly across devices."
            textSize = 14f
            setTextColor(subTextColor)
            gravity = Gravity.CENTER
            setLineSpacing(dpToPx(activity, 2).toFloat(), 1.15f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dpToPx(activity, 32)
                val horizontalMargin = dpToPx(activity, 12)
                setMargins(horizontalMargin, 0, horizontalMargin, dpToPx(activity, 32))
            }
        }
        contentLayout.addView(tvSub)

        // --- Middle Flexible Space (Pushes controls neatly down) ---
        val middleSpace = Space(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1.4f
            )
        }
        contentLayout.addView(middleSpace)

        // --- 3. ACCOUNT SELECTION CHIP / CARD ---
        val lastGoogleAccount = GoogleSignIn.getLastSignedInAccount(activity)
        val cachedUser = HazeAccountManager.getUser(activity)
        
        var selectedAccountName = lastGoogleAccount?.displayName 
            ?: cachedUser?.displayName 
            ?: "Google Account"
        var selectedAccountEmail = lastGoogleAccount?.email 
            ?: cachedUser?.email 
            ?: "Sign in to synchronize data"
        var selectedPhotoUrl = lastGoogleAccount?.photoUrl?.toString() 
            ?: cachedUser?.photoUrl

        val cardAccount = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx(activity, 68)
            ).apply {
                bottomMargin = dpToPx(activity, 18)
            }
            background = GradientDrawable().apply {
                cornerRadius = dpToPx(activity, 24).toFloat()
                setColor(cardBgColor)
            }
            val padH = dpToPx(activity, 16)
            setPadding(padH, 0, padH, 0)
            isClickable = true
            isFocusable = true
        }

        val ivAvatar = ImageView(activity).apply {
            val avatarSize = dpToPx(activity, 44)
            layoutParams = LinearLayout.LayoutParams(avatarSize, avatarSize).apply {
                marginEnd = dpToPx(activity, 14)
            }
        }
        HazeProfileImageHelper.loadAvatar(
            activity,
            selectedPhotoUrl,
            selectedAccountName.take(1).ifEmpty { "H" },
            ivAvatar,
            dpToPx(activity, 44)
        )
        cardAccount.addView(ivAvatar)

        val accountInfoLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val tvAccountName = TextView(activity).apply {
            text = selectedAccountName
            textSize = 15f
            setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
            setTextColor(textColor)
            maxLines = 1
        }

        val tvAccountEmail = TextView(activity).apply {
            text = selectedAccountEmail
            textSize = 13f
            setTextColor(subTextColor)
            maxLines = 1
        }

        accountInfoLayout.addView(tvAccountName)
        accountInfoLayout.addView(tvAccountEmail)
        cardAccount.addView(accountInfoLayout)

        val ivDropdown = ImageView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(dpToPx(activity, 22), dpToPx(activity, 22))
            setImageResource(R.drawable.ic_wide_arrow_down)
            setColorFilter(subTextColor)
        }
        cardAccount.addView(ivDropdown)
        contentLayout.addView(cardAccount)

        // --- 4. PRIMARY ACTION BUTTON ("Continue as ...") ---
        val btnContinue = FrameLayout(activity).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx(activity, 50)
            ).apply {
                bottomMargin = dpToPx(activity, 10)
            }
            background = GradientDrawable().apply {
                cornerRadius = dpToPx(activity, 25).toFloat()
                setColor(brandColor)
            }
            isClickable = true
            isFocusable = true
        }

        val givenName = lastGoogleAccount?.givenName 
            ?: selectedAccountName.split(" ").firstOrNull() 
            ?: "User"
        val continueText = if (lastGoogleAccount != null || cachedUser != null) {
            "Continue as $givenName"
        } else {
            "Continue with Google"
        }

        val tvContinue = TextView(activity).apply {
            text = continueText
            textSize = 15f
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

        // --- 5. SECONDARY ACTION ("Stay signed out") ---
        val btnStaySignedOut = TextView(activity).apply {
            text = "Stay signed out"
            textSize = 14f
            setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL))
            setTextColor(brandColor)
            gravity = Gravity.CENTER
            setBackgroundResource(getSelectableRes(activity))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dpToPx(activity, 44)
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dpToPx(activity, 16)
            }
            val pad = dpToPx(activity, 16)
            setPadding(pad, 0, pad, 0)
            isClickable = true
            isFocusable = true
        }
        contentLayout.addView(btnStaySignedOut)

        // --- 6. FOOTER / LEGAL DISCLOSURE ---
        val tvFooter = TextView(activity).apply {
            text = "By continuing, you agree to the Terms of Service. To help improve Haze, usage statistics may be sent to your telemetry preferences."
            textSize = 11.5f
            setTextColor(subtleTextColor)
            gravity = Gravity.CENTER
            setLineSpacing(dpToPx(activity, 1).toFloat(), 1.15f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                val horizontalMargin = dpToPx(activity, 8)
                setMargins(horizontalMargin, 0, horizontalMargin, dpToPx(activity, 8))
            }
        }
        contentLayout.addView(tvFooter)

        root.addView(contentLayout)

        // --- INTERACTION LOGIC ---

        // Account picker trigger
        cardAccount.setOnClickListener {
            activity.triggerStrongHaptic(it)
            activity.launchGoogleSignIn(
                onSuccess = { user ->
                    tvAccountName.text = user.displayName
                    tvAccountEmail.text = user.email
                    tvContinue.text = "Continue as ${user.givenName}"
                    HazeProfileImageHelper.loadAvatar(
                        activity,
                        user.photoUrl,
                        user.givenName.take(1),
                        ivAvatar,
                        dpToPx(activity, 44)
                    )
                },
                onError = {
                    // Ignored or silent
                }
            )
        }

        // Primary Action
        btnContinue.setOnClickListener {
            activity.triggerStrongHaptic(it)
            btnContinue.isEnabled = false
            btnStaySignedOut.isEnabled = false
            cardAccount.isEnabled = false
            tvContinue.text = "Setting up Haze..."

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
                    btnStaySignedOut.isEnabled = true
                    cardAccount.isEnabled = true
                    tvContinue.text = continueText
                    Toast.makeText(activity, errorMsg, Toast.LENGTH_SHORT).show()
                }
            )
        }

        // Secondary Action ("Stay signed out")
        btnStaySignedOut.setOnClickListener {
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
