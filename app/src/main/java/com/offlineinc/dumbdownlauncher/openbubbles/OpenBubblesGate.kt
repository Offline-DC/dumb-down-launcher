package com.offlineinc.dumbdownlauncher.openbubbles

import android.content.Context
import android.util.Log

/**
 * OpenBubbles ("smart txt") helper. The "must update before using" modal/gate
 * has been removed — smart txt always opens. What remains here is the one-time
 * "delete after 3 days" retention toggle, applied when the user leaves
 * OpenBubbles (see [com.offlineinc.dumbdownlauncher.launcher.MouseAccessibilityService]).
 */
object OpenBubblesGate {

    const val PKG = "com.openbubbles.messaging"

    private const val PREFS = "launcher_prefs"
    private const val RETENTION_APPLIED_KEY = "ob_retention_3day_applied_v1"
    private const val TAG = "OpenBubblesGate"

    /** Whether the one-time "delete after 3 days" retention toggle has run. */
    fun isRetentionApplied(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(RETENTION_APPLIED_KEY, false)

    /**
     * Apply the one-time "delete after 3 days" retention toggle, off the main
     * thread. This is the idempotency guard: [RETENTION_APPLIED_KEY] is set ONLY
     * after a successful write, and checked first, so the toggle is written
     * exactly once.
     *
     * [OpenBubblesOps.applyDeleteOldMessages] only writes when OpenBubbles is at
     * [OpenBubblesOps.MIN_SUPPORTED_VERSION_CODE] or newer; every other case
     * (older version, prefs file absent, OB focused, not installed) THROWS, so
     * the flag stays unset and a later trigger retries. Net effect: the toggle
     * lands exactly once, the first time OB is on the target build and editable.
     *
     * MUST be called when OpenBubbles is NOT in the foreground (the edit kills
     * OB first). Triggered from the accessibility OB→background transition.
     */
    fun applyRetentionOnceAsync(context: Context) {
        val appContext = context.applicationContext
        val alreadyApplied = isRetentionApplied(appContext)
        Thread {
            // Always log current state for cross-device diagnosis, even when
            // the one-time guard short-circuits.
            OpenBubblesOps.logRetentionState(appContext, TAG)
            Log.i(TAG, "applyRetentionOnceAsync: alreadyAppliedFlag=$alreadyApplied")
            if (alreadyApplied) return@Thread
            try {
                OpenBubblesOps.applyDeleteOldMessages(appContext, TAG)
                appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putBoolean(RETENTION_APPLIED_KEY, true).apply()
                Log.i(TAG, "retention toggle APPLIED — flag set, won't run again")
                OpenBubblesOps.logRetentionState(appContext, TAG)
            } catch (e: Exception) {
                Log.w(TAG, "retention apply deferred (flag stays unset, will retry): ${e.message}")
            }
        }.start()
    }
}
