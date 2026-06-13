package com.offlineinc.dumbdownlauncher.messenger

import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.offlineinc.dumbdownlauncher.pairing.PairingStore

internal const val GM_ANNOUNCE_TAG = "GMAnnounce"

/**
 * Debounce window for the whole "open android smart txt" action. On the flip
 * phone a single OK/center press can deliver more than one event, so the launch
 * fires twice in quick succession. Any second call within this window is dropped
 * (the activity launch modes dedupe too, but this avoids the churn).
 */
private const val DEBOUNCE_MS = 1200L

@Volatile
private var lastHandledElapsedMs = 0L

/**
 * Entry point for opening android smart txt (Google Messages). On the very first
 * open it shows the one-time "Google Messages is built in now" announcement
 * ([GoogleMessagesAnnouncementActivity]) — which tells the user to update the
 * Dumb Down app and re-run Configuration, then opens the messenger itself. On
 * every later open it goes straight to the messenger.
 *
 * The announcement is its OWN Activity (not an AlertDialog floating over the
 * caller): during onboarding the caller screen transitions right after launch,
 * which was destroying a floating dialog before the user could read or dismiss
 * it. A dedicated Activity has a stable lifecycle and a real focusable button.
 */
fun launchAndroidSmartTxt(activity: AppCompatActivity) {
    val now = SystemClock.elapsedRealtime()
    val sinceLast = now - lastHandledElapsedMs
    if (sinceLast in 0 until DEBOUNCE_MS) {
        Log.w(GM_ANNOUNCE_TAG, "launchAndroidSmartTxt: ignoring duplicate (${sinceLast}ms since last)")
        return
    }
    lastHandledElapsedMs = now

    if (PairingStore(activity).gmessagesAnnouncementShown) {
        Log.d(GM_ANNOUNCE_TAG, "launchAndroidSmartTxt: already announced — opening messenger")
        startMessenger(activity)
    } else {
        Log.i(GM_ANNOUNCE_TAG, "launchAndroidSmartTxt: first open — showing announcement activity")
        activity.startActivity(
            Intent(activity, GoogleMessagesAnnouncementActivity::class.java),
        )
        activity.overridePendingTransition(0, 0)
    }
}

/** Open the in-app Google Messages messenger. Shared by the direct path and the
 *  announcement activity's "got it" handler. */
internal fun startMessenger(activity: AppCompatActivity) {
    Log.i(GM_ANNOUNCE_TAG, "startMessenger: launching MessengerActivity")
    activity.startActivity(
        Intent(activity, MessengerActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
    activity.overridePendingTransition(0, 0)
}
