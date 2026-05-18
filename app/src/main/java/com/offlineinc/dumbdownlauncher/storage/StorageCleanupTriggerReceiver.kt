package com.offlineinc.dumbdownlauncher.storage

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Manual triggers for the four [StorageCleanupOps] cleanup paths so each
 * can be exercised from adb without going through the Free Up Space UI:
 *
 * ```
 * adb shell am broadcast \
 *   -a com.offlineinc.dumbdownlauncher.RUN_TRIM_APP_CACHES \
 *   -n com.offlineinc.dumbdownlauncher/.storage.StorageCleanupTriggerReceiver
 *
 * adb shell am broadcast \
 *   -a com.offlineinc.dumbdownlauncher.RUN_CLEAR_ANTENNAPOD \
 *   -n com.offlineinc.dumbdownlauncher/.storage.StorageCleanupTriggerReceiver
 *
 * adb shell am broadcast \
 *   -a com.offlineinc.dumbdownlauncher.RUN_CLEAR_SPOTIFY_OFFLINE \
 *   -n com.offlineinc.dumbdownlauncher/.storage.StorageCleanupTriggerReceiver
 *
 * adb shell am broadcast \
 *   -a com.offlineinc.dumbdownlauncher.RUN_CLEAR_APPLE_MUSIC_OFFLINE \
 *   -n com.offlineinc.dumbdownlauncher/.storage.StorageCleanupTriggerReceiver
 * ```
 *
 * Exported with no permission — actions are launcher-namespaced and the
 * worst case if another app fires one is performing a cleanup the user
 * would have done from the UI anyway. Mirrors the receiver shapes used
 * by [com.offlineinc.dumbdownlauncher.calllog.CallLogCleanupTriggerReceiver]
 * and friends.
 */
class StorageCleanupTriggerReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.i(TAG, "onReceive: action=$action")
        val pendingResult = goAsync()
        Thread({
            try {
                val ctx = context.applicationContext
                when (action) {
                    ACTION_TRIM_APP_CACHES -> {
                        val r = StorageCleanupOps.trimAppCaches(ctx, TAG)
                        Log.i(TAG, "trim app caches finished — freed=${r.bytesFreedDisplay}")
                    }
                    ACTION_CLEAR_ANTENNAPOD -> {
                        val r = StorageCleanupOps.clearAntennaPodEpisodes(ctx, TAG)
                        Log.i(TAG, "clear AntennaPod finished — freed=${r.bytesFreedDisplay}")
                    }
                    ACTION_CLEAR_SPOTIFY_OFFLINE -> {
                        val r = StorageCleanupOps.clearSpotifyOffline(ctx, TAG)
                        Log.i(TAG, "clear Spotify offline finished — freed=${r.bytesFreedDisplay}")
                    }
                    ACTION_CLEAR_APPLE_MUSIC_OFFLINE -> {
                        val r = StorageCleanupOps.clearAppleMusicOffline(ctx, TAG)
                        Log.i(TAG, "clear Apple Music offline finished — freed=${r.bytesFreedDisplay}")
                    }
                    else -> Log.w(TAG, "unknown action: $action")
                }
            } catch (e: Exception) {
                Log.w(TAG, "onReceive: cleanup threw — ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                pendingResult.finish()
            }
        }, "StorageCleanupTrigger").start()
    }

    companion object {
        private const val TAG = "StorageCleanupTrigger"
        const val ACTION_TRIM_APP_CACHES =
            "com.offlineinc.dumbdownlauncher.RUN_TRIM_APP_CACHES"
        const val ACTION_CLEAR_ANTENNAPOD =
            "com.offlineinc.dumbdownlauncher.RUN_CLEAR_ANTENNAPOD"
        const val ACTION_CLEAR_SPOTIFY_OFFLINE =
            "com.offlineinc.dumbdownlauncher.RUN_CLEAR_SPOTIFY_OFFLINE"
        const val ACTION_CLEAR_APPLE_MUSIC_OFFLINE =
            "com.offlineinc.dumbdownlauncher.RUN_CLEAR_APPLE_MUSIC_OFFLINE"
    }
}
