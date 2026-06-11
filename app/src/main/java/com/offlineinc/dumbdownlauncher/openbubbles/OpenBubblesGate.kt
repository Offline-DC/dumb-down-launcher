package com.offlineinc.dumbdownlauncher.openbubbles

import android.content.Context
import android.content.Intent
import android.util.Log
import com.offlineinc.dumbdownlauncher.launcher.PlatformPreferences
import com.offlineinc.dumbdownlauncher.update.UpdateNotificationManager

/**
 * Central gate for OpenBubbles ("smart txt") launches. Shared by two callers so
 * the policy can't drift:
 *
 *  - [com.offlineinc.dumbdownlauncher.MainAppsGridActivity] — a pre-launch
 *    fast path so the common home-grid tap is gated with no visible flash.
 *  - [com.offlineinc.dumbdownlauncher.launcher.MouseAccessibilityService] — the
 *    catch-all: it sees OpenBubbles come to the foreground no matter how it was
 *    launched (All Apps, a notification tap, recents, …) and gates it there.
 */
object OpenBubblesGate {

    const val PKG = "com.openbubbles.messaging"

    private const val PREFS = "launcher_prefs"
    private const val RETENTION_APPLIED_KEY = "ob_retention_3day_applied_v1"
    private const val TAG = "OpenBubblesGate"

    /**
     * True when the user should be routed to install a pending Smart Txt
     * (OpenBubbles) update before using the app: iOS messaging mode, OpenBubbles
     * installed, AND an update notification currently posted.
     *
     * The modal is gated on the update tile being live — NOT on a fixed version
     * floor. The update worker only posts that tile when GitHub's `version_code`
     * is higher than what's installed, so this fires for ANY newer build. (The
     * old fixed-threshold check meant the modal stopped once the device passed
     * 20002231, even when newer updates were available — that's the bug this
     * fixes.) [OpenBubblesOps.MIN_SUPPORTED_VERSION_CODE] still governs the
     * one-time retention toggle, just not this gate.
     *
     * If no update is waiting we let them in (can't force an update they can't
     * get yet).
     */
    fun isUpdateRequired(context: Context): Boolean {
        if (PlatformPreferences.getChoice(context) != "ios") return false
        // Block on the persisted pending state — but NOT once that update has
        // failed to install ([isOpenBubblesUpdateBlocking] handles the failed
        // carve-out), so a failing update can't lock the user out of messaging.
        // Shows on EVERY open until installed (or until it fails), survives
        // reboots, and doesn't depend on the live notification being posted.
        return UpdateNotificationManager.isOpenBubblesUpdateBlocking(context)
    }

    /** Show the hard-block update modal over the current screen. */
    fun showUpdateRequired(context: Context) {
        val intent = Intent(context, OpenBubblesUpdateRequiredActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION)
        // FLAG_ACTIVITY_NEW_TASK is required only from a non-Activity context
        // (the accessibility service). From an Activity (grid / All Apps /
        // notifications) we deliberately omit it so the modal stacks on top of
        // the caller and reliably shows EVERY time. With NEW_TASK, repeat opens
        // re-surfaced the existing launcher task — which had the notifications
        // page on top — instead of the modal (the "modal only shows once" bug).
        if (context !is android.app.Activity) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

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
     * OB first). Triggered from the install-success path and the accessibility
     * OB→background transition.
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
