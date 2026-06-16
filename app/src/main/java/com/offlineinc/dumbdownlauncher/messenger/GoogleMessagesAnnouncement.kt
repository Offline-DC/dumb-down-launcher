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
 * Entry point for opening android smart txt (Google Messages). On the first open
 * AFTER AN UPDATE it shows the one-time "Google Messages is built in now"
 * announcement ([GoogleMessagesAnnouncementActivity]) — which tells the user to
 * update the Dumb Down app and re-run Configuration, then opens the messenger
 * itself. Phones that shipped with this build (fresh installs, never updated)
 * already have the reworked Messages, so they skip the announcement entirely. On
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

    val store = PairingStore(activity)
    when {
        store.gmessagesAnnouncementShown -> {
            Log.d(GM_ANNOUNCE_TAG, "launchAndroidSmartTxt: already announced — opening messenger")
            startMessenger(activity)
        }
        isFreshInstall(activity) -> {
            // Phone shipped with this build (installed, never updated) → the user
            // already has the reworked Google Messages, so "new and improved" is
            // not news to them. Burn the one-time flag and go straight in; only
            // users who UPDATED into the rework should see the announcement.
            Log.i(GM_ANNOUNCE_TAG, "launchAndroidSmartTxt: fresh install — skipping announcement")
            store.gmessagesAnnouncementShown = true
            startMessenger(activity)
        }
        else -> {
            Log.i(GM_ANNOUNCE_TAG, "launchAndroidSmartTxt: updated into this build — showing announcement")
            activity.startActivity(
                Intent(activity, GoogleMessagesAnnouncementActivity::class.java),
            )
            activity.overridePendingTransition(0, 0)
        }
    }
}

/**
 * True if the app was installed at this version and never updated over-the-top —
 * i.e. the phone shipped with the build, or it's a brand-new install — as opposed
 * to an existing user who updated into it. Uses the package's install vs. update
 * timestamps, so it needs no prior version bookkeeping. On any error we return
 * false (→ show the announcement), since showing it once too often is safer than
 * silently hiding an important "you must re-run setup" notice.
 */
private fun isFreshInstall(activity: AppCompatActivity): Boolean = runCatching {
    val p = activity.packageManager.getPackageInfo(activity.packageName, 0)
    p.firstInstallTime == p.lastUpdateTime
}.getOrDefault(false)

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
