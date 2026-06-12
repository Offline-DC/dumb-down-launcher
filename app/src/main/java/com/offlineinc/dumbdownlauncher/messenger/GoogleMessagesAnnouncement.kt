package com.offlineinc.dumbdownlauncher.messenger

import android.os.SystemClock
import android.util.Log
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.offlineinc.dumbdownlauncher.pairing.PairingStore

private const val TAG = "GMAnnounce"

/**
 * Debounce window for the whole "open android smart txt" action. On the flip
 * phone a single OK/center press can deliver more than one event, so the launch
 * fires twice in quick succession. Without this, the second call would either
 * proceed immediately (blowing past the modal the first call just showed) or
 * double-launch the messenger (which bounces back to the grid). Any second call
 * within this window is dropped.
 */
private const val DEBOUNCE_MS = 1200L

@Volatile
private var lastHandledElapsedMs = 0L

/**
 * One-time announcement shown the first time the user opens android smart txt
 * (Google Messages). Google Messages now runs in-app on the dumb phone, and the
 * smartphone-side flow changed — so the modal tells the user to update the Dumb
 * Down app and re-run Configuration to finish setup.
 *
 * Shown at most once (gated by [PairingStore.gmessagesAnnouncementShown]); after
 * it's dismissed — or on every open after the first — [onProceed] runs to open
 * the messenger as usual. Duplicate launches from a single keypress are dropped
 * (see [DEBOUNCE_MS]).
 */
fun maybeShowGoogleMessagesAnnouncement(
    activity: AppCompatActivity,
    onProceed: () -> Unit,
) {
    val now = SystemClock.elapsedRealtime()
    val sinceLast = now - lastHandledElapsedMs
    if (sinceLast in 0 until DEBOUNCE_MS) {
        Log.w(TAG, "maybeShow: ignoring duplicate launch (${sinceLast}ms since last)")
        return
    }
    lastHandledElapsedMs = now

    val store = PairingStore(activity)
    if (store.gmessagesAnnouncementShown) {
        Log.d(TAG, "maybeShow: already announced — proceeding straight to messenger")
        onProceed()
        return
    }
    // Mark shown up front so backgrounding / rotating doesn't re-trigger it.
    store.gmessagesAnnouncementShown = true
    Log.i(TAG, "maybeShow: showing one-time announcement")

    // Proceed exactly once, however the dialog is dismissed.
    var proceeded = false
    val proceedOnce = {
        if (!proceeded) {
            proceeded = true
            Log.i(TAG, "dialog dismissed — launching messenger")
            onProceed()
        }
    }

    val dialog = AlertDialog.Builder(activity)
        .setTitle("google messages is built in now")
        .setMessage(
            "smart txt now uses google messages right on your dumb phone.\n\n" +
                "to set it up:\n" +
                "1. update the dumb down app on your smartphone\n" +
                "2. open it and go through configuration again\n\n" +
                "then follow the steps to sign in."
        )
        .setPositiveButton("got it") { d, _ -> d.dismiss() }
        // Only the button dismisses — back / tap-outside won't blow past it.
        .setCancelable(false)
        .setOnDismissListener { proceedOnce() }
        .create()

    // The OK/center keypress that launched smart txt can carry through (its
    // key-up arrives here) and instantly click the auto-focused "got it"
    // button, dismissing the modal before it can be read. Disable the button
    // briefly after the dialog shows so that stray press is ignored; re-enable
    // it after a beat so the user can dismiss it deliberately.
    dialog.setOnShowListener {
        val ok = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        ok.isEnabled = false
        Log.d(TAG, "dialog shown — 'got it' disabled for ${BUTTON_ENABLE_DELAY_MS}ms")
        ok.postDelayed({
            ok.isEnabled = true
            Log.d(TAG, "'got it' re-enabled")
        }, BUTTON_ENABLE_DELAY_MS)
    }
    dialog.show()
}

/** How long the "got it" button stays disabled after the modal appears, to
 *  swallow the launching keypress and give the user time to read it. */
private const val BUTTON_ENABLE_DELAY_MS = 1500L
