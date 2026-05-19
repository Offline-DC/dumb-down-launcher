package com.offlineinc.dumbdownlauncher.ui.mousetutorial

import android.content.Context
import android.media.RingtoneManager
import android.util.Log

private const val TAG = "MouseTutorial.PlayQuack"

/** Play the device's default notification sound as a "quack".  Errors are
 *  swallowed and logged — the sound is a UX nicety, not a hard
 *  requirement for the tutorial to function. */
internal fun playQuack(context: Context) {
    try {
        val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val ringtone = RingtoneManager.getRingtone(context, uri)
        ringtone?.play()
    } catch (t: Throwable) {
        Log.w(TAG, "Could not play quack sound: ${t.message}")
    }
}
