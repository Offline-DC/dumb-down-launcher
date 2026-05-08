package com.offlineinc.dumbdownlauncher.openbubbles

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Manual trigger for [OpenBubblesAttachmentCleanupWorker]'s deletion
 * logic so the cleanup can be exercised on demand via adb, bypassing
 * JobScheduler / WorkManager:
 *
 * ```
 * adb shell am broadcast \
 *   -a com.offlineinc.dumbdownlauncher.RUN_OB_ATTACHMENT_CLEANUP \
 *   -n com.offlineinc.dumbdownlauncher/.openbubbles.OpenBubblesAttachmentCleanupTriggerReceiver
 * ```
 *
 * Calls the same [OpenBubblesOps.clearAttachments] helper the worker
 * uses, so verifying the broadcast verifies the production code path.
 * Mirrors `CallLogCleanupTriggerReceiver`.
 *
 * Exported with no permission — the action name is launcher-namespaced
 * and the worst case if another app fires it is clearing the OpenBubbles
 * attachment cache, which is the user's intended behaviour anyway.
 */
class OpenBubblesAttachmentCleanupTriggerReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Log.i(TAG, "onReceive: action=${intent.action} — running OB attachment cleanup off-thread")
        // goAsync lets us hold the broadcast open while OpenBubblesOps
        // does multiple su invocations. Without it, the broadcast would
        // return immediately and the process could be torn down before
        // the kill / find / rm sequence finishes.
        val pendingResult = goAsync()
        Thread({
            try {
                val result = OpenBubblesOps.clearAttachments(TAG)
                Log.i(TAG, "onReceive: cleanup finished — was=${result.bytesFreedDisplay}")
            } catch (e: IllegalStateException) {
                Log.i(TAG, "onReceive: deferred — ${e.message}")
            } catch (e: Exception) {
                Log.w(TAG, "onReceive: cleanup threw — ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                pendingResult.finish()
            }
        }, "OBAttachmentCleanupTrigger").start()
    }

    companion object {
        private const val TAG = "OBAttachmentTrigger"
        const val ACTION_RUN_CLEANUP = "com.offlineinc.dumbdownlauncher.RUN_OB_ATTACHMENT_CLEANUP"
    }
}
