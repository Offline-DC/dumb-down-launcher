package com.offlineinc.dumbdownlauncher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Receives the TOGGLE_TYPESYNC broadcast fired by FlipMouse on a star-key
 * long-press.
 *
 * Type Sync now auto-connects at launcher startup and stays open permanently,
 * so this receiver is a no-op.  Kept registered in AndroidManifest for
 * backwards-compatibility with older FlipMouse builds that still send the
 * broadcast.
 */
class TypeSyncToggleReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "TypeSyncToggle"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        // No-op — Type Sync is always on.  Older FlipMouse versions may
        // still fire this broadcast; just ignore it silently.
        Log.d(TAG, "Received TOGGLE_TYPESYNC broadcast (no-op)")
    }
}
