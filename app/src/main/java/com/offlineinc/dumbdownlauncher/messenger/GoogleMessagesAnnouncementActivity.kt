package com.offlineinc.dumbdownlauncher.messenger

import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.offlineinc.dumbdownlauncher.pairing.PairingStore

/**
 * One-time "Google Messages is built in now" announcement, shown the first time
 * the user opens android smart txt. Its own Activity (rather than an AlertDialog
 * over the caller) so its lifecycle is stable — the onboarding caller transitions
 * right after launch, which previously destroyed a floating dialog before the
 * user could read or dismiss it.
 *
 * Marks the one-time flag on create, shows the message, and on dismiss opens the
 * messenger and finishes. Entered via [launchAndroidSmartTxt].
 */
class GoogleMessagesAnnouncementActivity : AppCompatActivity() {

    private var proceeded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PairingStore(this).gmessagesAnnouncementShown = true
        Log.i(GM_ANNOUNCE_TAG, "announcement activity created")

        val dialog = AlertDialog.Builder(this)
            .setTitle("new and improved google messages")
            .setMessage(
                "1. update Dumb Down app on smartphone\n" +
                    "2. click Configuration and go through setup again"
            )
            .setPositiveButton("got it") { d, _ -> d.dismiss() }
            // Only the button advances — back / tap-outside can't blow past it.
            .setCancelable(false)
            .setOnDismissListener { proceed() }
            .create()

        // The OK/center keypress that launched smart txt can carry through and
        // instantly click the auto-focused "got it" button. Disable it briefly
        // after the dialog shows so that stray press is ignored.
        dialog.setOnShowListener {
            val ok = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            ok.isEnabled = false
            Log.d(GM_ANNOUNCE_TAG, "dialog shown — 'got it' disabled for ${BUTTON_ENABLE_DELAY_MS}ms")
            ok.postDelayed({
                ok.isEnabled = true
                Log.d(GM_ANNOUNCE_TAG, "'got it' re-enabled")
            }, BUTTON_ENABLE_DELAY_MS)
        }
        dialog.show()
    }

    private fun proceed() {
        if (proceeded) return
        proceeded = true
        Log.i(GM_ANNOUNCE_TAG, "got it -> launching messenger")
        startMessenger(this)
        finish()
    }

    companion object {
        private const val BUTTON_ENABLE_DELAY_MS = 1500L
    }
}
