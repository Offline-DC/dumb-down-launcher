package com.offlineinc.dumbdownlauncher.openbubbles

import android.app.NotificationManager
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
     * True when OpenBubbles is in iOS messaging mode, older than
     * [OpenBubblesOps.MIN_SUPPORTED_VERSION_CODE], AND an update notification is
     * currently posted — i.e. the user should be routed to install the update
     * before using the app. If no update is waiting we let them in (can't force
     * an update they can't get yet).
     */
    fun isUpdateRequired(context: Context): Boolean {
        if (PlatformPreferences.getChoice(context) != "ios") return false
        val vc = OpenBubblesOps.installedVersionCode(context) ?: return false
        if (vc >= OpenBubblesOps.MIN_SUPPORTED_VERSION_CODE) return false
        return isUpdateNotificationActive(context)
    }

    /**
     * Whether the launcher currently has the OpenBubbles update notification
     * posted — the same "update available" tile the notifications page shows.
     * The launcher posts it itself, so it appears in getActiveNotifications()
     * with no extra permission.
     */
    private fun isUpdateNotificationActive(context: Context): Boolean {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return false
        return try {
            nm.activeNotifications.any {
                it.id == UpdateNotificationManager.NOTIFICATION_ID_OPENBUBBLES
            }
        } catch (_: Exception) {
            false
        }
    }

    /** Show the hard-block update modal over the current screen. */
    fun showUpdateRequired(context: Context) {
        context.startActivity(
            Intent(context, OpenBubblesUpdateRequiredActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        )
    }

    /** Whether the one-time "delete after 3 days" retention toggle has run. */
    fun isRetentionApplied(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(RETENTION_APPLIED_KEY, false)

    /**
     * Apply the one-time "delete after 3 days" retention toggle, off the main
     * thread. No-op if already applied.
     *
     * MUST be called when OpenBubbles is NOT in the foreground:
     * [OpenBubblesOps.applyDeleteOldMessages] kills OB before editing its prefs
     * and throws if OB is the focused app — in which case the flag stays unset
     * and the next background transition retries. The flag is only set when the
     * apply returns cleanly (applied, or an intentional no-op: OB up-to-date /
     * already set / not installed).
     */
    fun applyRetentionOnceAsync(context: Context) {
        val appContext = context.applicationContext
        if (isRetentionApplied(appContext)) return
        Thread {
            try {
                OpenBubblesOps.applyDeleteOldMessages(appContext, TAG)
                appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putBoolean(RETENTION_APPLIED_KEY, true).apply()
                Log.i(TAG, "retention toggle applied")
            } catch (e: Exception) {
                Log.w(TAG, "retention apply deferred (will retry on next background): ${e.message}")
            }
        }.start()
    }
}
