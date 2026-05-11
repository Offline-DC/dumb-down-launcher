package com.offlineinc.dumbdownlauncher.coverdisplay

import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.IBinder
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
 *  • onDisplayRemoved → dismiss CoverPresentation (phone was opened)
 *  • onStartCommand   → idempotent re-attach in case service is restarted
 */
class CoverDisplayService : Service() {

    companion object {
        private const val TAG = "CoverDisplayService"
    }

    private var presentation: CoverPresentation? = null
    private lateinit var displayManager: DisplayManager

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {
            Log.d(TAG, "Display added: $displayId")
            tryAttach()
        }

        override fun onDisplayRemoved(displayId: Int) {
            Log.d(TAG, "Display removed: $displayId")
            presentation?.dismiss()
            presentation = null
        }

        override fun onDisplayChanged(displayId: Int) { /* no-op */ }
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
        displayManager.unregisterDisplayListener(displayListener)
        presentation?.dismiss()
        presentation = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Finds the first DISPLAY_CATEGORY_PRESENTATION display (the cover screen)
     * and shows [CoverPresentation] on it. Idempotent — does nothing if already attached.
     */
    private fun tryAttach() {
        if (presentation != null) return

        val displays: Array<Display> = displayManager.getDisplays(
            DisplayManager.DISPLAY_CATEGORY_PRESENTATION
        )

        if (displays.isEmpty()) {
            Log.d(TAG, "No presentation display found — waiting for onDisplayAdded")
            return
        }

        val target = displays[0]
        Log.d(TAG, "Attaching to display ${target.displayId} (${target.name})")

        try {
            presentation = CoverPresentation(this, target).also { it.show() }
        } catch (e: Exception) {
            Log.e(TAG, "Could not show presentation: ${e.message}")
            presentation = null
        }
    }
}
