package com.offlineinc.dumbdownlauncher.diagnostics

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Foreground service that owns the rolling logcat tail.
 *
 * Goal: when the device randomly reboots (or a call drops, etc.),
 * there is always an in-progress segment file on disk holding roughly
 * the last hour of log lines that a support engineer can pull and
 * inspect — plus enough trailing history (up to 24 hours of gzipped
 * segments) to look for the same fault recurring earlier in the day.
 *
 * Scope is deliberately tight — no post-mortem capture, no per-event
 * classification, no JSONL. The conversation moved from "diagnose the
 * reboot/dropped-call cause" to "make sure the logs from before the
 * event are always harvestable", and this service is the single piece
 * of machinery that needs to be running for that to work.
 *
 * Started from [com.offlineinc.dumbdownlauncher.DumbDownApp.onCreate]
 * via [startIfEnabled] (gated by the runtime opt-in alone — no
 * compile-time flag) and from the rolling-adb-logs toggle in
 * [com.offlineinc.dumbdownlauncher.AllAppsActivity]. Runs as
 * START_STICKY so the OS restarts it after low-memory kills — exactly
 * the case where we'd otherwise lose the recent log lines we want.
 *
 * The user-visible notification reads "Logging diagnostics"
 * and instructs the user how to turn collection off (long-press
 * quack) so the foreground-service notification doubles as
 * disclosure. No persistent UI state otherwise.
 */
class RebootLoggingService : Service() {

    private val tag = "RebootLoggingSvc"

    private lateinit var store: RebootLoggingStore
    private var rollingLogcat: RollingLogcatTail? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()

        // Foreground-service contract (Android 8+): once the OS promotes
        // us via startForegroundService(), we MUST call startForeground()
        // within ~5s or it throws RemoteServiceException. Satisfy first,
        // bail second.
        store = RebootLoggingStore(this)
        if (!store.enabled) {
            startForegroundPlaceholderThenStop()
            return
        }

        startForeground(RebootLoggingConfig.NOTIFICATION_ID, buildNotification())

        // Rolling logs are kept ONLY in the app-private diag dir — no /sdcard
        // mirror. The bundle is pulled via "submit logs" (uploads the private
        // dir) or, with root, straight from /data/data/<pkg>/files/diag/.
        val privateRoot = DiagPaths.privateDiagDir(this)
        rollingLogcat = RollingLogcatTail(privateRoot).also { it.start() }

        Log.i(tag, "diagnostic logging service started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!this::store.isInitialized || !store.enabled) {
            stopSelf()
            return START_NOT_STICKY
        }
        // Sticky so the OS restarts us after a low-memory kill — that's
        // exactly the timeline we want to keep observing.
        return START_STICKY
    }

    override fun onDestroy() {
        try { rollingLogcat?.stop() } catch (_: Throwable) {}
        rollingLogcat = null
        // If the user turned rolling adb logs OFF (store.enabled == false),
        // discard the rolling-logcat tree this service collected. Done here,
        // after the tail is stopped and its writer is closed, rather than
        // from DiagnosticsActivity — so the tail thread can't recreate
        // current.log in the gap between an activity-side delete and the
        // service finishing teardown. Gated on !store.enabled so an OS
        // low-memory kill (enabled stays true, sticky restart resumes
        // collection) never wipes the logs.
        if (this::store.isInitialized && !store.enabled) {
            runCatching { DiagPaths.clearRollingLogs(this) }
                .onFailure { Log.w(tag, "clearRollingLogs on teardown failed", it) }
        }
        super.onDestroy()
    }

    // ── Foreground-service notification ──────────────────────────────────

    private fun startForegroundPlaceholderThenStop() {
        try {
            startForeground(RebootLoggingConfig.NOTIFICATION_ID, buildNotification())
        } catch (t: Throwable) {
            Log.w(tag, "placeholder startForeground failed", t)
        }
        stopSelf()
    }

    private fun buildNotification(): Notification {
        ensureChannel()
        // Reuse the launcher icon so the device looks no different in
        // the shade. Fall back defensively if the resource lookup ever
        // changes name.
        val iconRes = try {
            resources.getIdentifier("ic_launcher_round", "mipmap", packageName)
                .takeIf { it != 0 } ?: android.R.drawable.stat_sys_data_bluetooth
        } catch (_: Throwable) { android.R.drawable.stat_sys_data_bluetooth }

        return NotificationCompat.Builder(this, RebootLoggingConfig.NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(iconRes)
            .setContentTitle("Logging diagnostics")
            .setContentText("Long press quack in all apps to stop")
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(RebootLoggingConfig.NOTIFICATION_CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            RebootLoggingConfig.NOTIFICATION_CHANNEL_ID,
            "Diagnostic logging",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shown while the launcher is recording a rolling 24-hour " +
                "diagnostic log. Toggled from a long press on quack in all apps."
            setShowBadge(false)
            enableLights(false)
            enableVibration(false)
        }
        nm.createNotificationChannel(channel)
    }

    companion object {
        /** Start the service if the runtime opt-in is on. Safe to call repeatedly. */
        fun startIfEnabled(context: Context) {
            val store = RebootLoggingStore(context)
            if (!store.enabled) return
            val intent = Intent(context, RebootLoggingService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, RebootLoggingService::class.java)
            context.stopService(intent)
        }
    }
}
