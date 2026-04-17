package com.offlineinc.dumbdownlauncher.quack

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.util.Calendar
import java.util.TimeZone

/**
 * Fires every Monday at 9:00 AM Eastern to kick off the weekly quack
 * notification cycle.
 *
 * On receive:
 * 1. Check persisted location — bail if none (can't poll without coords).
 * 2. Post "be the first to quack" notification.
 * 3. Enqueue [QuackFirstQuackWorker] to poll every 15 min for 24 h.
 * 4. Re-schedule alarm for next Monday.
 *
 * No network I/O happens here — the worker handles all API calls.
 */
class QuackMondayAlarmReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "QuackMondayAlarm"
        const val ACTION = "com.offlineinc.dumbdownlauncher.QUACK_MONDAY"
        private const val PREFS = "quack_monday_alarm"
        private const val KEY_LAST_FIRED_WEEK = "last_fired_week"
        private val EASTERN = TimeZone.getTimeZone("America/New_York")

        /**
         * Schedule the next Monday 9 AM Eastern alarm.
         *
         * Handles the "missed alarm on reboot" case: if it's currently Monday
         * in Eastern time and we haven't fired this week yet, schedules for
         * right now instead of next week.
         */
        fun scheduleNext(context: Context) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = makePendingIntent(context)

            val nowEastern = Calendar.getInstance(EASTERN)
            val currentWeek = weekKey(nowEastern)
            val lastFired = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_LAST_FIRED_WEEK, null)

            val triggerAt: Long
            if (nowEastern.get(Calendar.DAY_OF_WEEK) == Calendar.MONDAY
                && nowEastern.get(Calendar.HOUR_OF_DAY) >= 9
                && lastFired != currentWeek
            ) {
                // It's Monday after 9 AM and we haven't fired — fire now.
                triggerAt = System.currentTimeMillis() + 5_000L // 5s grace
                Log.i(TAG, "Missed Monday alarm — scheduling immediate fire")
            } else {
                triggerAt = nextMonday9amEastern()
                Log.i(TAG, "Scheduled next Monday alarm at $triggerAt (${java.util.Date(triggerAt)})")
            }

            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        }

        fun cancelAlarm(context: Context) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.cancel(makePendingIntent(context))
            Log.i(TAG, "Alarm cancelled")
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
         * Returns epoch millis of the next Monday at 9:00 AM Eastern.
         * If it's currently Monday before 9 AM Eastern, returns today at 9 AM.
         */
        private fun nextMonday9amEastern(): Long {
            val cal = Calendar.getInstance(EASTERN)
            // If it's Monday before 9 AM, target today
            if (cal.get(Calendar.DAY_OF_WEEK) == Calendar.MONDAY
                && cal.get(Calendar.HOUR_OF_DAY) < 9
            ) {
                cal.set(Calendar.HOUR_OF_DAY, 9)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                return cal.timeInMillis
            }
            // Otherwise advance to next Monday
            val daysUntilMonday = (Calendar.MONDAY - cal.get(Calendar.DAY_OF_WEEK) + 7) % 7
            val daysToAdd = if (daysUntilMonday == 0) 7 else daysUntilMonday
            cal.add(Calendar.DAY_OF_YEAR, daysToAdd)
            cal.set(Calendar.HOUR_OF_DAY, 9)
            cal.set(Calendar.MINUTE, 0)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            return cal.timeInMillis
        }

        /** Year+week key for dedup, e.g. "2026-W16". */
        private fun weekKey(cal: Calendar): String {
            val year = cal.get(Calendar.YEAR)
            val week = cal.get(Calendar.WEEK_OF_YEAR)
            return "$year-W$week"
        }

        private fun markFiredThisWeek(context: Context) {
            val key = weekKey(Calendar.getInstance(EASTERN))
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_LAST_FIRED_WEEK, key)
                .apply()
        }
    }

    override fun onReceive(context: Context, intent: Intent?) {
        Log.i(TAG, "Monday alarm fired")

        // Respect the user's mute preference from the rulez page
        val muted = context.getSharedPreferences("quack_prefs", Context.MODE_PRIVATE)
            .getBoolean("notifications_muted", false)
        if (muted) {
            Log.i(TAG, "Notifications muted — skipping, rescheduling")
            scheduleNext(context)
            return
        }

        // Record the fire timestamp for the 24h polling cutoff
        QuackFirstQuackWorker.recordAlarmFired(context)
        markFiredThisWeek(context)

        // Check location — bail if we can't poll
        val loc = QuackLocationStore.load(context)
        if (loc == null || loc.ageMs >= QuackLocationStore.STALE_MAX_AGE_MS) {
            Log.w(TAG, "No usable location — skipping notification, rescheduling")
            scheduleNext(context)
            return
        }

        // Post "be the first to quack" — the worker corrects it if someone
        // already quacked within the first 15-min poll.
        QuackNotificationManager.notifyBeFirst(context)

        // Start 15-min polling for up to 24h
        QuackFirstQuackWorker.schedule(context)

        // Re-schedule for next Monday
        scheduleNext(context)
        Log.i(TAG, "Posted notification and started polling worker")
    }
}
