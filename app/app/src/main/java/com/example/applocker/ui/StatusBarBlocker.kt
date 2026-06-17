package com.example.applocker.ui

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * Blocks the status/notification bar by placing an invisible overlay
 * at the top of the screen that consumes all touch events,
 * preventing the user from pulling down the notification shade.
 */
class StatusBarBlocker(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var blockerView: View? = null

    fun isActive(): Boolean = blockerView != null

    fun enable() {
        if (isActive()) return

        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getMetrics(metrics)

        // Height of the blocker = status bar height + a bit extra to catch swipe gestures
        val statusBarHeight = getStatusBarHeight()
        val blockerHeight = statusBarHeight + 10 // small extra margin

        val view = View(context).apply {
            setBackgroundColor(0x00000000) // fully transparent
            setOnTouchListener { _, event ->
                // Consume ALL touches on the status bar area
                true
            }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            blockerHeight,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            // FLAG_NOT_FOCUSABLE: don't steal keyboard focus
            // FLAG_NOT_TOUCH_MODAL: allow touches OUTSIDE the overlay to pass through
            // FLAG_LAYOUT_IN_SCREEN: position relative to screen, not window
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }

        blockerView = view
        try {
            windowManager.addView(view, params)
        } catch (e: Exception) {
            e.printStackTrace()
            blockerView = null
        }
    }

    fun disable() {
        blockerView?.let {
            try { windowManager.removeView(it) } catch (_: Exception) {}
            blockerView = null
        }
    }

    private fun getStatusBarHeight(): Int {
        val resId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resId > 0) context.resources.getDimensionPixelSize(resId) else 80
    }
}
