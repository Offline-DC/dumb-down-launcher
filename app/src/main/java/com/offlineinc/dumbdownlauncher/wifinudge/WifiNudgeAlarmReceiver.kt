package com.offlineinc.dumbdownlauncher.wifinudge

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.Calendar

/**
 * Fires on the 2nd Tuesday and 4th Sunday of every month at 10:00 AM local
 * time. On fire, checks whether the device is connected to Wi-Fi; if not,
 * posts the [WifiNudgeNotificationManager.notifyAddWifi] notification so
 * the user can tap straight into the Wi-Fi settings page and pick a
 * network. Then re-arms itself for whichever of the two target days
 * comes next.
 *
 * Patterned on [com.offlineinc.dumbdownlauncher.quack.QuackMondayAlarmReceiver]:
 *  - Single alarm slot, re-scheduled on every fire.
 *  - Uses [AlarmManager.setExactAndAllowWhileIdle] so it lands on time even
 *    in Doze.
 *  - Idempotent — the launcher's HOME process runs onCreate on every boot,
 *    so [scheduleNext] is the canonical call site and is safe to invoke
 *    repeatedly.
 *  - Independent dedup keys per occurrence so a missed alarm on reboot
 *    fires once instead of stacking up.
 *
 * Boot-time nudge is also handled here via [nudgeOnBootIfOffline], which
 * the launcher's HOME process calls from `onCreate` on every cold start.
 * Because Wi-Fi may not have re-associated yet at the instant `onCreate`
 * runs (modem and supplicant come up in parallel with the app process),
 * the check is deferred by [BOOT_NUDGE_DELAY_MS] so we don't false-positive
 * on a phone that is about to be on Wi-Fi a few seconds later.
 */
class WifiNudgeAlarmReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "WifiNudgeAlarm"
        const val ACTION = "com.offlineinc.dumbdownlauncher.WIFI_NUDGE"
        private const val PREFS = "wifi_nudge_alarm"
        private const val KEY_LAST_FIRED = "last_fired_occurrence"

        // 10 AM local time — late enough that the user is awake and on a
        // typical morning route (commute / coffee shop) where a Wi-Fi
        // selector is plausibly useful.
        private const val HOUR_OF_DAY = 10
        private const val MINUTE = 0

        // Delay between launcher cold-start and the boot-time Wi-Fi check.
        // Long enough that the supplicant has typically associated with a
        // saved network (~5–15s on the TCL Flip Go), short enough that the
        // user sees the nudge in the same session if they really are off
        // Wi-Fi. Posted on the main looper so the process can die before
        // it fires without us holding any extra thread alive.
        private const val BOOT_NUDGE_DELAY_MS = 30_000L

        /**
         * Schedule the next 2nd-Tuesday or 4th-Sunday alarm, whichever
         * comes sooner. Handles the "missed alarm" case: if we're past
         * an occurrence time today and haven't fired for it yet, fire
         * immediately with a 5-second grace.
         */
        fun scheduleNext(context: Context) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = makePendingIntent(context)

            val now = Calendar.getInstance()
            val lastFired = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_LAST_FIRED, null)
            val currentOccurrence = currentOccurrenceKey(now)

            val triggerAt: Long
            if (currentOccurrence != null
                && (now.get(Calendar.HOUR_OF_DAY) > HOUR_OF_DAY
                    || (now.get(Calendar.HOUR_OF_DAY) == HOUR_OF_DAY
                        && now.get(Calendar.MINUTE) >= MINUTE))
                && lastFired != currentOccurrence
            ) {
                // It's an occurrence day, past 10 AM, and we haven't fired
                // for this one — fire now.
                triggerAt = System.currentTimeMillis() + 5_000L
                Log.i(TAG, "Missed $currentOccurrence — scheduling immediate fire")
            } else {
                triggerAt = nextOccurrenceMillis(now)
                Log.i(TAG, "Scheduled next wifi-nudge alarm at $triggerAt (${java.util.Date(triggerAt)})")
            }

            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        }

        fun cancelAlarm(context: Context) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.cancel(makePendingIntent(context))
            Log.i(TAG, "Alarm cancelled")
        }

        /**
         * Boot-time nudge. Called from [DumbDownApp.onCreate] on every
         * launcher process start (so: every cold boot, plus app updates).
         * Posts the same "add wifi 2 save on data" notification if the
         * device is not connected to Wi-Fi at the time of the check.
         *
         * The check is deferred by [BOOT_NUDGE_DELAY_MS] because the
         * supplicant is typically still associating with a saved network
         * when the launcher process first comes up — checking immediately
         * would false-positive on a device that's about to be on Wi-Fi a
         * few seconds later.
         *
         * Uses the application context (lifetime = process) and posts to
         * the main looper. If the process dies inside the delay window,
         * the runnable is dropped harmlessly.
         */
        fun nudgeOnBootIfOffline(context: Context) {
            val appContext = context.applicationContext
            Handler(Looper.getMainLooper()).postDelayed({
                if (isOnWifi(appContext)) {
                    Log.i(TAG, "Boot check — on Wi-Fi, skipping nudge")
                    return@postDelayed
                }
                Log.i(TAG, "Boot check — not on Wi-Fi, posting nudge")
                try {
                    WifiNudgeNotificationManager.notifyAddWifi(appContext)
                } catch (e: Exception) {
                    Log.w(TAG, "Boot-time nudge failed", e)
                }
            }, BOOT_NUDGE_DELAY_MS)
        }

        private fun makePendingIntent(context: Context): PendingIntent {
            val intent = Intent(ACTION).apply { setPackage(context.packageName) }
            return PendingIntent.getBroadcast(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        /**
         * True iff the device's currently-active network is reporting the
         * Wi-Fi transport. Cellular-only (or no network at all) returns
         * false. Bluetooth tethering / Ethernet count as "not Wi-Fi" — the
         * nudge is about wifi savings specifically.
         */
        private fun isOnWifi(context: Context): Boolean {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE)
                as? ConnectivityManager ?: return false
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        }

        /**
         * Returns epoch millis of the next 2nd-Tuesday-or-4th-Sunday
         * occurrence strictly after [from]. Walks forward day-by-day from
         * tomorrow; at most ~31 iterations so the cost is negligible.
         *
         * "Strictly after" means that if [from] is exactly on an occurrence
         * boundary at 10 AM we still advance to the next one — the alarm
         * has already fired by the time we're scheduling its successor.
         */
        private fun nextOccurrenceMillis(from: Calendar): Long {
            // Start from tomorrow at HOUR_OF_DAY:MINUTE local time so we
            // never re-schedule for the same instant we just fired at.
            val cal = (from.clone() as Calendar).apply {
                add(Calendar.DAY_OF_YEAR, 1)
                set(Calendar.HOUR_OF_DAY, HOUR_OF_DAY)
                set(Calendar.MINUTE, MINUTE)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            // Bounded loop: any 31-day window covers at least one
            // 2nd-Tuesday and one 4th-Sunday so this always finds a hit.
            repeat(45) {
                if (isTargetOccurrence(cal)) return cal.timeInMillis
                cal.add(Calendar.DAY_OF_YEAR, 1)
            }
            // Should be unreachable — fall back to a week out so the
            // alarm chain doesn't break.
            Log.w(TAG, "No occurrence found in 45 days — falling back to +7d")
            return from.timeInMillis + 7L * 24 * 60 * 60 * 1000
        }

        /**
         * Returns a stable string key for the occurrence that [cal] falls
         * on (e.g. "2026-05-T2" for the 2nd Tuesday of May 2026), or null
         * if [cal] is not on an occurrence day. Used to dedupe so a
         * missed-alarm-on-reboot fires at most once per occurrence.
         */
        private fun currentOccurrenceKey(cal: Calendar): String? {
            if (!isTargetOccurrence(cal)) return null
            val year = cal.get(Calendar.YEAR)
            val month = cal.get(Calendar.MONTH) + 1
            val tag = when (cal.get(Calendar.DAY_OF_WEEK)) {
                Calendar.TUESDAY -> "T2"
                Calendar.SUNDAY -> "S4"
                else -> return null
            }
            return "%04d-%02d-%s".format(year, month, tag)
        }

        /**
         * True iff [cal] falls on either the 2nd Tuesday or 4th Sunday of
         * its month. Pure date predicate — ignores time-of-day.
         */
        private fun isTargetOccurrence(cal: Calendar): Boolean {
            val dow = cal.get(Calendar.DAY_OF_WEEK)
            // DAY_OF_WEEK_IN_MONTH = ((DAY_OF_MONTH - 1) / 7) + 1, so the
            // 2nd Tuesday has DAY_OF_WEEK_IN_MONTH == 2, etc.
            val nth = cal.get(Calendar.DAY_OF_WEEK_IN_MONTH)
            return (dow == Calendar.TUESDAY && nth == 2)
                || (dow == Calendar.SUNDAY && nth == 4)
        }

        private fun markFired(context: Context, key: String) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_LAST_FIRED, key)
                .apply()
        }
    }

    override fun onReceive(context: Context, intent: Intent?) {
        Log.i(TAG, "wifi-nudge alarm fired")

        val now = Calendar.getInstance()
        val occurrence = currentOccurrenceKey(now)
        if (occurrence != null) {
            markFired(context, occurrence)
        }

        try {
            if (isOnWifi(context)) {
                Log.i(TAG, "Already on Wi-Fi — skipping notification")
            } else {
                Log.i(TAG, "Not on Wi-Fi — posting nudge")
                WifiNudgeNotificationManager.notifyAddWifi(context)
            }
        } catch (e: Exception) {
            Log.w(TAG, "notifyAddWifi failed — rescheduling anyway", e)
        }

        // Always re-arm so the alarm chain survives this fire.
        scheduleNext(context)
    }
}
