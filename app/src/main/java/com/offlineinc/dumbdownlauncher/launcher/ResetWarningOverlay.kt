package com.offlineinc.dumbdownlauncher.launcher

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Draws a persistent bottom-sheet overlay warning the user that a factory
 * reset will wipe the Dumb Down Launcher software.
 *
 * Uses TYPE_ACCESSIBILITY_OVERLAY — no SYSTEM_ALERT_WINDOW permission needed.
 * The accessibility service's grant covers this window type.
 *
 * Lifecycle:
 *   • show()          — called when the Settings reset screen is detected
 *   • hide()          — called when the user leaves Settings or dismisses manually
 *   • clearDismissed()— called when the user navigates away from the reset page,
 *                       so the overlay can appear again next time they visit
 */
object ResetWarningOverlay {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var overlayView: android.view.View? = null
    private var windowManager: WindowManager? = null

    /**
     * Set to true when the user explicitly taps "got it".
     * Prevents the overlay from immediately re-appearing when the accessibility
     * service receives the window event caused by removing the overlay window
     * while the Settings reset page is still in the foreground.
     * Cleared by [clearDismissed] when the user navigates away from the reset page.
     */
    @Volatile var userDismissed: Boolean = false
        private set

    val isShowing: Boolean get() = overlayView != null

    fun show(context: Context) {
        mainHandler.post {
            if (overlayView != null) {
                Log.d("RESET_WARN", "show(): overlay already visible — skipping")
                return@post
            }
            if (userDismissed) {
                Log.d("RESET_WARN", "show(): user already dismissed — skipping until they navigate away")
                return@post
            }
            Log.d("RESET_WARN", "show(): adding overlay window")

            val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            windowManager = wm

            // ── Root container ────────────────────────────────────────────
            val container = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.parseColor("#E6000000"))   // 90 % opaque black
                setPadding(dp(context, 16), dp(context, 14), dp(context, 16), dp(context, 14))
                gravity = Gravity.CENTER_HORIZONTAL
            }

            // ── Title ─────────────────────────────────────────────────────
            container.addView(TextView(context).apply {
                text = "⚠  heads up"
                setTextColor(Color.parseColor("#FAF594"))           // Dumb yellow
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                gravity = Gravity.CENTER
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            })

            // ── Body ──────────────────────────────────────────────────────
            container.addView(TextView(context).apply {
                text = "A factory reset will wipe your dumbOS and is not recommended. For assistance, email support@dumb.co or call our help line 4047163605"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                gravity = Gravity.CENTER
                setPadding(0, dp(context, 8), 0, dp(context, 10))
            })

            // ── Dismiss button ────────────────────────────────────────────
            container.addView(Button(context).apply {
                text = "got it"
                setTextColor(Color.BLACK)
                setBackgroundColor(Color.parseColor("#FAF594"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setPadding(dp(context, 24), dp(context, 6), dp(context, 24), dp(context, 6))
                setOnClickListener {
                    Log.d("RESET_WARN", "dismiss button tapped — hiding and suppressing re-show")
                    userDismissed = true
                    hide()
                }
            })

            // ── Window params ─────────────────────────────────────────────
            // FLAG_NOT_TOUCH_MODAL: touches outside our view pass through to
            //   Settings beneath (user can still scroll / tap in Settings).
            // Not setting FLAG_NOT_FOCUSABLE so the dismiss button is clickable.
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.BOTTOM
            }

            try {
                wm.addView(container, params)
                overlayView = container
                Log.d("RESET_WARN", "show(): overlay window added successfully")
            } catch (e: Exception) {
                Log.e("RESET_WARN", "show(): failed to add overlay window — ${e.message}")
            }
        }
    }

    fun hide() {
        mainHandler.post {
            val view = overlayView ?: return@post
            Log.d("RESET_WARN", "hide(): removing overlay window")
            try {
                windowManager?.removeView(view)
            } catch (e: Exception) {
                Log.w("RESET_WARN", "hide(): removeView failed — ${e.message}")
            }
            overlayView = null
            windowManager = null
        }
    }

    /**
     * Clears the user-dismissed flag so the overlay can appear again the next
     * time the reset page is visited. Call this when the accessibility service
     * determines the user has navigated away from the Settings reset screen.
     */
    fun clearDismissed() {
        if (userDismissed) {
            Log.d("RESET_WARN", "clearDismissed(): reset — overlay can show again next visit")
            userDismissed = false
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density + 0.5f).toInt()
}
