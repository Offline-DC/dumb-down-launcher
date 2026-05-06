package com.offlineinc.dumbdownlauncher.calllog

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Manual trigger for [CallLogCleanupWorker]'s deletion logic.
 *
 * Exists primarily so the cleanup can be exercised on demand via adb,
 * bypassing JobScheduler / WorkManager entirely:
 *
 * ```
 * adb shell am broadcast \
 *   -a com.offlineinc.dumbdownlauncher.RUN_CALL_LOG_CLEANUP \
 *   -n com.offlineinc.dumbdownlauncher/.calllog.CallLogCleanupTriggerReceiver
 * ```
 *
 * The receiver runs the same [CallLogCleanupWorker.runCleanupNow] helper
 * that the scheduled worker uses, so verification here is verification
 * of the production code path. Useful for ops debugging on devices where
 * the periodic schedule is misbehaving (e.g., an OEM-flavored MediaTek
 * build that doesn't dispatch WorkManager jobs predictably from a
 * `cmd jobscheduler run -f` invocation).
 *
 * Exposed at the manifest level with no permission — the action name is
 * launcher-namespaced and the worst case if another app fires it is
 * deleting call-log rows older than a week, which is the user's intended
 * behaviour anyway.
 */
class CallLogCleanupTriggerReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Log.i(TAG, "onReceive: action=${intent.action} — running cleanup synchronously off-thread")
        // goAsync lets us hold the broadcast open while we do disk I/O
        // (su grant + content provider delete) on a background thread.
        // Without it the broadcast would return immediately and the
        // process could be torn down before the delete completes.
        val pendingResult = goAsync()
        Thread({
            try {
                val deleted = CallLogCleanupWorker.runCleanupNow(context)
                Log.i(TAG, "onReceive: cleanup finished — runCleanupNow returned $deleted")
            } catch (e: Exception) {
                Log.w(TAG, "onReceive: cleanup threw — ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                pendingResult.finish()
            }
        }, "CallLogCleanupTrigger").start()
    }

    companion object {
        private const val TAG = "CallLogCleanupTrigger"
        const val ACTION_RUN_CLEANUP = "com.offlineinc.dumbdownlauncher.RUN_CALL_LOG_CLEANUP"
    }
}
