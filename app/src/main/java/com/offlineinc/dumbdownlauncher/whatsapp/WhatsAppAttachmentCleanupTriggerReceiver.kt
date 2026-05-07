package com.offlineinc.dumbdownlauncher.whatsapp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Manual trigger for [WhatsAppAttachmentCleanupWorker]'s deletion logic so
 * the cleanup can be exercised on demand via adb, bypassing JobScheduler /
 * WorkManager:
 *
 * ```
 * adb shell am broadcast \
 *   -a com.offlineinc.dumbdownlauncher.RUN_WA_ATTACHMENT_CLEANUP \
 *   -n com.offlineinc.dumbdownlauncher/.whatsapp.WhatsAppAttachmentCleanupTriggerReceiver
 * ```
 *
 * Calls the same [WhatsAppOps.clearOldAttachments] helper the worker uses,
 * so verifying the broadcast verifies the production code path. Mirrors
 * [com.offlineinc.dumbdownlauncher.openbubbles.OpenBubblesAttachmentCleanupTriggerReceiver].
 *
 * Designed to pair with `scripts/whatsapp_probe.sh`: run the probe to
 * snapshot pre-cleanup state, fire this broadcast, run the probe again
 * to confirm the file count and disk-usage numbers in the three target
 * subdirs (`.Links/`, `WhatsApp Images/`, `WhatsApp Video/`) dropped as
 * expected. Other subdirs (Voice Notes, Documents, etc.) should be
 * unchanged.
 *
 * Exported with no permission — the action name is launcher-namespaced
 * and the worst case if another app fires it is running the same 7-day
 * rolling cleanup the worker performs nightly at 2 AM, which is the
 * user's intended retention policy.
 */
class WhatsAppAttachmentCleanupTriggerReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Log.i(TAG, "onReceive: action=${intent.action} — running WA attachment cleanup off-thread")
        // goAsync lets us hold the broadcast open while WhatsAppOps does
        // multiple su invocations (one per target subdir, plus the
        // existence checks). Without it, the broadcast would return
        // immediately and the process could be torn down before the
        // find / delete sequence finishes.
        val pendingResult = goAsync()
        Thread({
            try {
                val result = WhatsAppOps.clearOldAttachments(CUTOFF_DAYS, TAG)
                Log.i(
                    TAG,
                    "onReceive: cleanup finished — deleted=${result.filesDeleted} " +
                        "was=${result.bytesFreedDisplay}"
                )
            } catch (e: Exception) {
                Log.w(TAG, "onReceive: cleanup threw — ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                pendingResult.finish()
            }
        }, "WAAttachmentCleanupTrigger").start()
    }

    companion object {
        private const val TAG = "WAAttachmentTrigger"

        /**
         * Same value as [WhatsAppAttachmentCleanupWorker]'s `CUTOFF_DAYS`.
         * Hardcoded here too rather than exposed publicly because the
         * trigger broadcast is meant to exercise the EXACT production
         * path, not a configurable one.
         */
        private const val CUTOFF_DAYS = 7

        const val ACTION_RUN_CLEANUP =
            "com.offlineinc.dumbdownlauncher.RUN_WA_ATTACHMENT_CLEANUP"
    }
}
