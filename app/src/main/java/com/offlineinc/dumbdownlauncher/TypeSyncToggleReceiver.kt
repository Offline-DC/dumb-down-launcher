package com.offlineinc.dumbdownlauncher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.offlineinc.dumbdownlauncher.pairing.PairingStore

/**
 * Receives the TOGGLE_TYPESYNC broadcast fired by FlipMouse on a star-key
 * long-press and toggles the WebKeyboardService on/off.
 *
 * Registered in AndroidManifest.xml with exported=true so the `am broadcast`
 * command (running as shell/root from FlipMouse) can reach it.
 */
class TypeSyncToggleReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "TypeSyncToggle"
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onReceive(context: Context, intent: Intent?) {
        Log.i(TAG, "Received TOGGLE_TYPESYNC broadcast")

        val pairingStore = PairingStore(context)

        if (!pairingStore.isPaired) {
            showToast(context, "pair with ur smartphone first in device setup")
            Log.w(TAG, "Not paired — ignoring toggle")
            return
        }

        if (WebKeyboardService.isRunning) {
            // Turn OFF
            context.startService(
                Intent(context, WebKeyboardService::class.java).apply {
                    action = WebKeyboardService.ACTION_STOP
                }
            )
            showToast(context, "type sync off")
            Log.i(TAG, "Type sync toggled OFF")
        } else {
            // Turn ON
            context.startService(
                Intent(context, WebKeyboardService::class.java).apply {
                    action = WebKeyboardService.ACTION_START
                    putExtra(WebKeyboardService.EXTRA_PHONE_NUMBER, pairingStore.flipPhoneNumber)
                }
            )
            showToast(context, "type sync on for 5 min")
            Log.i(TAG, "Type sync toggled ON")
        }
    }

    private fun showToast(context: Context, message: String) {
        mainHandler.post {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }
}
