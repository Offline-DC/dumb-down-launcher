package com.offlineinc.dumbdownlauncher.coverdisplay

import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Display

/**
 * Manages the Presentation window on the cover display (128×128 secondary screen).
 *
 * Started from [com.offlineinc.dumbdownlauncher.DumbDownApp] on app create.
 * Because this app is the system HOME launcher, its process is kept alive by
 * Android automatically — no foreground service notification is required.
 *
 * Display lifecycle:
 *  • onDisplayAdded   → create and show CoverPresentation
 *  • onDisplayChanged → re-attempt attach when the display state settles
 *                       (UNKNOWN → ON/OFF). On TCL/MediaTek builds the cover
 *                       screen can flip into UNKNOWN state during a service
 *                       restart, which makes the initial onCreate tryAttach
 *                       bail with `getDisplays()` returning empty. The
 *                       listener is the only thing that wakes us back up
 *                       since the display already exists — no add event is
 *                       coming. Without this hook the service stays wedged
 *                       on the system-default cover until the next reboot
 *                       or a force-stop (observed in the wild for 8+ hours).
 *  • onDisplayRemoved → dismiss CoverPresentation ONLY if it's OUR display.
 *                       The previous unconditional dismiss tore down the
 *                       cover whenever any unrelated virtual display
 *                       cycled — and we never recreated it because no
 *                       onDisplayAdded fires for a display that's still there.
 *  • onStartCommand   → idempotent re-attach in case service is restarted
 *
 * If `Presentation.show()` throws (InvalidDisplayException because the token
 * went stale between getDisplays() and show()) we schedule a small bounded
 * series of retries via [retryHandler] rather than wedging until the next
 * display event.
 */
class CoverDisplayService : Service() {

    companion object {
        private const val TAG = "CoverDisplayService"

        /**
         * Backoff schedule for show()-failure retries. Long enough to let
         * the platform resolve whatever transient state caused the display
         * token to be stale; short enough that the user doesn't sit on the
         * system-default cover for noticeable seconds. Budget exhausted →
         * fall back to waiting for the next display listener event.
         */
        private val RETRY_DELAYS_MS = longArrayOf(1_000L, 3_000L, 10_000L)
    }

    private var presentation: CoverPresentation? = null

    /**
     * displayId of the display [presentation] is bound to. Used so
     * onDisplayRemoved only dismisses when OUR display goes away — not when
     * an unrelated virtual display is cycled by the system. Cleared whenever
     * [presentation] is cleared.
     */
    private var attachedDisplayId: Int? = null

    private lateinit var displayManager: DisplayManager

    private val retryHandler = Handler(Looper.getMainLooper())
    private var retryAttempt = 0

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {
            Log.d(TAG, "Display added: $displayId")
            tryAttach()
        }

        override fun onDisplayRemoved(displayId: Int) {
            Log.d(TAG, "Display removed: $displayId (attached=$attachedDisplayId)")
            // Only dismiss when OUR display was removed. The previous
            // unconditional dismiss meant any unrelated virtual display
            // cycling out (KeyguardDisplay, screencap helpers, etc.) tore
            // down the cover presentation and we never recreated it,
            // because no onDisplayAdded would fire for the cover that's
            // still physically present.
            if (displayId == attachedDisplayId) {
                presentation?.dismiss()
                presentation = null
                attachedDisplayId = null
            }
        }

        override fun onDisplayChanged(displayId: Int) {
            // The cover display can flip into state=UNKNOWN during a service
            // restart (observed on TCL 4058G after START_STICKY revival). When
            // it later settles, the platform fires onDisplayChanged — not
            // onDisplayAdded, since the display never went away. If we're
            // not attached, this is our chance to retry. The presentation
            // null-check keeps this idempotent on the happy path where we're
            // already showing.
            if (presentation == null) {
                Log.d(TAG, "Display changed: $displayId — retrying attach")
                tryAttach()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        displayManager.registerDisplayListener(displayListener, null)
        tryAttach()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        tryAttach()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        retryHandler.removeCallbacksAndMessages(null)
        displayManager.unregisterDisplayListener(displayListener)
        presentation?.dismiss()
        presentation = null
        attachedDisplayId = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Picks a presentation-eligible display and shows [CoverPresentation] on
     * it. Idempotent — does nothing if already attached.
     *
     * Defensive about two failure modes observed on TCL/MediaTek:
     *   1. `getDisplays(DISPLAY_CATEGORY_PRESENTATION)` occasionally returns
     *      empty even when the cover display exists (state=UNKNOWN during a
     *      service restart). Fall back to scanning every display for
     *      [Display.FLAG_PRESENTATION] before giving up — see
     *      [pickPresentationDisplay].
     *   2. `Presentation.show()` can throw InvalidDisplayException when the
     *      display token went stale between getDisplays() and show().
     *      Schedule bounded retries via [scheduleRetry] instead of wedging
     *      until the next listener event.
     */
    private fun tryAttach() {
        if (presentation != null) return

        val target: Display? = pickPresentationDisplay()
        if (target == null) {
            Log.d(TAG, "No presentation display found — waiting for onDisplayAdded/Changed")
            return
        }

        Log.d(TAG, "Attaching to display ${target.displayId} (${target.name})")

        try {
            val p = CoverPresentation(this, target)
            p.show()
            // Only commit to fields after show() succeeds, so a mid-show
            // throw can't leave us holding a half-constructed Presentation.
            presentation = p
            attachedDisplayId = target.displayId
            // Successful attach wipes any pending retry from a prior failure.
            retryHandler.removeCallbacksAndMessages(null)
            retryAttempt = 0
        } catch (e: Exception) {
            Log.e(TAG, "Could not show presentation: ${e.message}")
            presentation = null
            attachedDisplayId = null
            scheduleRetry()
        }
    }

    /**
     * Returns the first presentation-eligible display, falling back to a
     * full scan of [DisplayManager.getDisplays] for [Display.FLAG_PRESENTATION]
     * when the category-filtered call returns empty. The fallback covers
     * the TCL/MediaTek quirk where a mid-restart cover display is briefly
     * excluded from `DISPLAY_CATEGORY_PRESENTATION` even though it still
     * carries the flag.
     */
    private fun pickPresentationDisplay(): Display? {
        val byCategory = displayManager.getDisplays(
            DisplayManager.DISPLAY_CATEGORY_PRESENTATION
        )
        if (byCategory.isNotEmpty()) return byCategory[0]

        return displayManager.displays.firstOrNull { display ->
            display.displayId != Display.DEFAULT_DISPLAY &&
                (display.flags and Display.FLAG_PRESENTATION) != 0
        }
    }

    /**
     * Posts a delayed [tryAttach] using the next entry in [RETRY_DELAYS_MS].
     * Resets to the start of the schedule whenever an attach succeeds (see
     * [tryAttach]) or the budget is exhausted. Bounded so we don't spin
     * forever on a permanently-broken display token.
     */
    private fun scheduleRetry() {
        val idx = retryAttempt
        if (idx >= RETRY_DELAYS_MS.size) {
            Log.w(TAG, "Retry budget exhausted — waiting for next display event")
            retryAttempt = 0
            return
        }
        val delay = RETRY_DELAYS_MS[idx]
        retryAttempt = idx + 1
        Log.d(TAG, "Scheduling retry #${retryAttempt} in ${delay}ms")
        retryHandler.postDelayed({ tryAttach() }, delay)
    }
}
