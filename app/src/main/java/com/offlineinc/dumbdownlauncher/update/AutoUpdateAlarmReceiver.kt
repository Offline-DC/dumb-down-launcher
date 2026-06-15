package com.offlineinc.dumbdownlauncher.update

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import android.util.Log
import com.offlineinc.dumbdownlauncher.BuildConfig
import com.offlineinc.dumbdownlauncher.launcher.NetworkUtils
import com.offlineinc.dumbdownlauncher.launcher.PlatformPreferences
import com.offlineinc.dumbdownlauncher.openbubbles.OpenBubblesOps
import com.offlineinc.dumbdownlauncher.pairing.PairingStore
import java.util.Calendar

/**
 * Nightly **automatic, unattended** update installer. Fires every day at
 * [HOUR_OF_DAY]:00 local time. When the device is on Wi-Fi *and* the battery is
 * at least [MIN_BATTERY_PCT]%, it checks GitHub for newer Dumb Down Launcher and
 * Smart Txt (OpenBubbles) releases and, for any that are out of date, downloads
 * and **silently installs** them via root (see [AutoUpdateInstaller]) — no
 * notification tap, no install dialog, while the user is asleep.
 *
 * This sits alongside the existing weekly [UpdateCheckWorker], which only
 * *notifies* about updates and waits for a tap. The two don't conflict: if the
 * nightly install lands first, the next weekly check finds nothing newer and
 * posts nothing.
 *
 * Patterned on [com.offlineinc.dumbdownlauncher.wifinudge.WifiNudgeAlarmReceiver]:
 *  - Single alarm slot, re-armed on every fire ([scheduleNext]).
 *  - [AlarmManager.setExactAndAllowWhileIdle] so 4 AM lands even in Doze.
 *  - Idempotent: the launcher's HOME process runs onCreate on every boot, so
 *    scheduleNext is the canonical call site and safe to call repeatedly.
 *  - A per-day dedupe key so a missed alarm (reboot near 4 AM) fires once
 *    rather than stacking.
 *
 * If conditions (Wi-Fi + battery) aren't met when the alarm fires, we simply
 * skip and wait for the next 4 AM — no intraday retry.
 */
class AutoUpdateAlarmReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "AutoUpdate"
        const val ACTION = "com.offlineinc.dumbdownlauncher.AUTO_UPDATE"
        private const val PREFS = "auto_update_alarm"
        private const val KEY_LAST_FIRED = "last_fired_day"

        // 4 AM local — user asleep, off-peak, and the silent install (which can
        // restart the launcher process when it updates itself) is invisible.
        private const val HOUR_OF_DAY = 4
        private const val MINUTE = 0

        /** Minimum battery percentage required before installing. */
        const val MIN_BATTERY_PCT = 40

        /**
         * Re-arm the next 4 AM alarm. If it's already past 4 AM today and we
         * haven't fired for today yet (e.g. the device booted at 4:05), fire
         * shortly to catch the missed slot; otherwise schedule tomorrow's.
         */
        fun scheduleNext(context: Context) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = makePendingIntent(context)

            val now = Calendar.getInstance()
            val today = dayKey(now)
            val lastFired = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_LAST_FIRED, null)

            val pastTodaysSlot = now.get(Calendar.HOUR_OF_DAY) > HOUR_OF_DAY ||
                (now.get(Calendar.HOUR_OF_DAY) == HOUR_OF_DAY && now.get(Calendar.MINUTE) >= MINUTE)

            val triggerAt = if (pastTodaysSlot && lastFired != today) {
                Log.i(TAG, "Missed today's 4 AM slot — scheduling immediate fire")
                System.currentTimeMillis() + 5_000L
            } else {
                nextSlotMillis(now)
            }
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            Log.i(TAG, "Auto-update alarm set for ${java.util.Date(triggerAt)}")
        }

        fun cancelAlarm(context: Context) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.cancel(makePendingIntent(context))
            Log.i(TAG, "Auto-update alarm cancelled")
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

        /** Epoch millis of the next [HOUR_OF_DAY]:[MINUTE], strictly after [from]. */
        private fun nextSlotMillis(from: Calendar): Long {
            val cal = (from.clone() as Calendar).apply {
                set(Calendar.HOUR_OF_DAY, HOUR_OF_DAY)
                set(Calendar.MINUTE, MINUTE)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            if (cal.timeInMillis <= from.timeInMillis) {
                cal.add(Calendar.DAY_OF_YEAR, 1)
            }
            return cal.timeInMillis
        }

        private fun dayKey(cal: Calendar): String = "%04d-%02d-%02d".format(
            cal.get(Calendar.YEAR),
            cal.get(Calendar.MONTH) + 1,
            cal.get(Calendar.DAY_OF_MONTH),
        )

        /** Current battery percentage (0–100), or null if it can't be read. */
        private fun batteryPercent(context: Context): Int? {
            val status = context.registerReceiver(
                null,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ) ?: return null
            val level = status.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = status.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            if (level < 0 || scale <= 0) return null
            return (level * 100) / scale
        }

        /**
         * True when the phone is actively in use right now. On this flip phone
         * the lid drives the screen directly — opening the lid wakes the screen,
         * closing it sleeps the screen — so "screen interactive" is the
         * production-safe proxy for "lid open and being used". (The hardware lid
         * switch isn't exposed via SensorManager / SW_LID on this device; the
         * only lid signal is a continuous root `getevent` stream owned by the
         * opt-in diagnostics service, which isn't available in normal builds —
         * see [com.offlineinc.dumbdownlauncher.diagnostics.LidSensorReader].)
         *
         * Conservative: if we can't read power state at all, treat the phone as
         * in use so we never install over an active session.
         */
        private fun isPhoneInUse(context: Context): Boolean {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
                ?: return true
            return pm.isInteractive
        }

        /**
         * The actual work: only run when the phone is idle (lid closed / screen
         * off) and gate on Wi-Fi + battery, then find what's out of date and
         * silently install it — so the latest build is waiting when the user
         * next opens the phone. Public + synchronous so the adb trigger receiver
         * can drive it directly. MUST be called off the main thread.
         */
        fun runAutoUpdate(context: Context) {
            if (isPhoneInUse(context)) {
                Log.i(TAG, "Skipping auto-update — phone in use (screen on / lid open)")
                return
            }
            if (!NetworkUtils.isOnWifi(context)) {
                Log.i(TAG, "Skipping auto-update — not on Wi-Fi")
                return
            }
            val pct = batteryPercent(context)
            if (pct == null || pct < MIN_BATTERY_PCT) {
                Log.i(TAG, "Skipping auto-update — battery $pct% < $MIN_BATTERY_PCT%")
                return
            }
            Log.i(TAG, "Conditions met (Wi-Fi + battery $pct%) — checking for updates")

            val latest = try {
                UpdateChecker.fetchLatest(PairingStore(context).betaTesterMode)
            } catch (t: Throwable) {
                Log.w(TAG, "release check failed — will retry at next 4 AM", t)
                return
            }

            // OpenBubbles FIRST: installing the launcher replaces our own
            // (running HOME) process, so do everything else before that.
            val obInfo = latest["openbubbles-messaging"]
            if (obInfo != null && PlatformPreferences.getChoice(context) == "ios") {
                val installed = OpenBubblesOps.installedVersionCode(context)
                if (installed != null && obInfo.versionCode > installed) {
                    Log.i(TAG, "Smart Txt update ${obInfo.versionName} (vc ${obInfo.versionCode} > $installed) — installing")
                    AutoUpdateInstaller.installOpenBubbles(context, obInfo.downloadUrl)
                } else {
                    Log.i(TAG, "Smart Txt up to date (installed=$installed)")
                }
            }

            // Launcher LAST — pm install of our own package restarts this process.
            val launcherInfo = latest["dumb-down-launcher"]
            if (launcherInfo != null && launcherInfo.versionCode > BuildConfig.VERSION_CODE) {
                Log.i(TAG, "Launcher update ${launcherInfo.versionName} (vc ${launcherInfo.versionCode} > ${BuildConfig.VERSION_CODE}) — installing")
                UpdateNotificationManager.cancel(
                    context,
                    UpdateNotificationManager.NOTIFICATION_ID_LAUNCHER,
                )
                AutoUpdateInstaller.installLauncher(context, launcherInfo.downloadUrl)
            } else {
                Log.i(TAG, "Launcher up to date (installed=${BuildConfig.VERSION_CODE})")
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent?) {
        Log.i(TAG, "auto-update alarm fired")
        // Mark fired for today so scheduleNext's missed-slot logic doesn't
        // immediately re-fire after we re-arm below.
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LAST_FIRED, dayKey(Calendar.getInstance()))
            .apply()

        // Work is network + multi-MB I/O + a `pm` subprocess — keep it off the
        // broadcast's main thread via goAsync().
        val pending = goAsync()
        Thread {
            try {
                runAutoUpdate(context)
            } catch (t: Throwable) {
                Log.e(TAG, "auto-update run failed", t)
            } finally {
                try {
                    scheduleNext(context)
                } catch (t: Throwable) {
                    Log.e(TAG, "re-arm failed", t)
                }
                try {
                    pending.finish()
                } catch (_: Throwable) {
                }
            }
        }.apply { isDaemon = true }.start()
    }
}
