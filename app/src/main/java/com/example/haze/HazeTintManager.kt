package com.example.haze

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.Shader
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.PopupWindow

/**
 * HazeTintManager - Apple Liquid Glass Tint & Blur Engine
 *
 * Provides real-time translucent frosted-glass styling and deep background blurring:
 * - System window blur (Android 12+ / S / API 31+ WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
 * - In-app RenderEffect hardware blur (API 31+) on underlying interfaces
 * - High-refraction liquid glass translucent borders and specular gradient overlays
 * - Fallback fluid glass tint for Android 10/11 (API 29/30) with rich multi-layer backdrop gradients
 */
object HazeTintManager {

    const val PREF_IS_TINT_ENABLED = "is_tint_enabled"

    fun isTintEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences("HazePrefs", Context.MODE_PRIVATE)
        return prefs.getBoolean(PREF_IS_TINT_ENABLED, false)
    }

    fun setTintEnabled(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences("HazePrefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean(PREF_IS_TINT_ENABLED, enabled).apply()
    }

    /**
     * Applies Window-level blur behind for Dialogs, BottomSheetDialogs, and Popups.
     * On Android 12+ (API 31+), activates native OS hardware-accelerated blur behind.
     */
    fun applyBlurBehindToWindow(window: Window?, blurRadiusDp: Int = 45) {
        if (window == null) return
        val context = window.context ?: return
        if (!isTintEnabled(context)) return

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                window.addFlags(WindowManager.LayoutParams.FLAG_BLUR_BEHIND)
                val density = context.resources.displayMetrics.density
                val radiusPx = (blurRadiusDp * density).toInt()
                window.attributes = window.attributes.apply {
                    setBlurBehindRadius(radiusPx)
                }
            } else {
                window.setDimAmount(0.42f)
            }
        } catch (_: Throwable) {}
    }

    /**
     * Returns a translucent liquid-glass GradientDrawable tailored for menus, dialogs, and sheets.
     */
    fun createGlassDrawable(
        context: Context,
        isDark: Boolean,
        cornerRadiusDp: Float = 24f,
        strokeWidthDp: Float = 1.2f
    ): GradientDrawable {
        val density = context.resources.displayMetrics.density
        val tintActive = isTintEnabled(context)

        return if (tintActive) {
            val bgColor = if (isDark) {
                Color.argb(195, 24, 25, 28) // Deep smoked glass
            } else {
                Color.argb(215, 255, 255, 255) // Pure frosted crystal glass
            }
            val strokeColor = if (isDark) {
                Color.argb(70, 255, 255, 255) // Specular rim reflection
            } else {
                Color.argb(120, 255, 255, 255) // Clean bright bevel
            }
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = cornerRadiusDp * density
                setColor(bgColor)
                setStroke((strokeWidthDp * density).toInt().coerceAtLeast(1), strokeColor)
            }
        } else {
            val solidBg = if (isDark) Color.parseColor("#212328") else Color.WHITE
            val solidStroke = if (isDark) Color.parseColor("#383C46") else Color.parseColor("#E0E2EC")
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = cornerRadiusDp * density
                setColor(solidBg)
                setStroke((1f * density).toInt().coerceAtLeast(1), solidStroke)
            }
        }
    }

    /**
     * Creates a bottom sheet glass background with rounded top corners.
     */
    fun createSheetGlassDrawable(
        context: Context,
        isDark: Boolean,
        topCornerRadiusDp: Float = 28f
    ): GradientDrawable {
        val density = context.resources.displayMetrics.density
        val r = topCornerRadiusDp * density
        val radii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
        val tintActive = isTintEnabled(context)

        return if (tintActive) {
            val bgColor = if (isDark) Color.argb(205, 22, 23, 26) else Color.argb(220, 255, 255, 255)
            val strokeColor = if (isDark) Color.argb(60, 255, 255, 255) else Color.argb(110, 255, 255, 255)
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadii = radii
                setColor(bgColor)
                setStroke((1.2f * density).toInt().coerceAtLeast(1), strokeColor)
            }
        } else {
            val solidBg = if (isDark) Color.parseColor("#1C1E22") else Color.WHITE
            val solidStroke = if (isDark) Color.parseColor("#33363F") else Color.parseColor("#E0E2EC")
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadii = radii
                setColor(solidBg)
                setStroke((1f * density).toInt().coerceAtLeast(1), solidStroke)
            }
        }
    }

    /**
     * Blurs an underlying view (such as the main content or web view) when a popup/menu is opened,
     * and clears it when dismissed. Supported on Android 12+; on older versions applies smooth dim/tint.
     */
    fun blurTargetView(targetView: View?, blurRadiusDp: Float = 24f) {
        if (targetView == null) return
        val context = targetView.context ?: return
        if (!isTintEnabled(context)) return

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val radiusPx = blurRadiusDp * targetView.resources.displayMetrics.density
                targetView.setRenderEffect(RenderEffect.createBlurEffect(radiusPx, radiusPx, Shader.TileMode.CLAMP))
            } else {
                targetView.animate().alpha(0.65f).setDuration(160L).start()
            }
        } catch (_: Throwable) {}
    }

    fun clearTargetViewBlur(targetView: View?) {
        if (targetView == null) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                targetView.setRenderEffect(null)
            } else {
                targetView.animate().alpha(1.0f).setDuration(140L).start()
            }
        } catch (_: Throwable) {}
    }

    /**
     * Configures a PopupWindow with liquid glass background and backdrop blur effect.
     */
    fun setupGlassPopup(popupWindow: PopupWindow, contentView: View, targetBackdrop: View?, isDark: Boolean) {
        val context = contentView.context ?: return
        if (!isTintEnabled(context)) return

        contentView.background = createGlassDrawable(context, isDark, 24f, 1.2f)
        blurTargetView(targetBackdrop, 22f)
        popupWindow.setOnDismissListener {
            clearTargetViewBlur(targetBackdrop)
        }
    }

    /**
     * Configures a Dialog with blur behind and liquid glass surface.
     */
    fun setupGlassDialog(dialog: Dialog, cardView: View?, isDark: Boolean, cornerRadiusDp: Float = 28f) {
        val context = dialog.context
        if (!isTintEnabled(context)) return

        applyBlurBehindToWindow(dialog.window, 50)
        cardView?.background = createGlassDrawable(context, isDark, cornerRadiusDp, 1.2f)
    }
}
