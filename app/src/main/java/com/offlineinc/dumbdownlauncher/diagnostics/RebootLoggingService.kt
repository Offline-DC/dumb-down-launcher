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
import com.offlineinc.dumbdownlauncher.BuildConfig

/**
 * Foreground service that owns the rolling logcat tail.
 *
 * Goal: when the device randomly reboots, there is always an
 * in-progress segment file on disk holding roughly the last hour of
 * log lines that a support engineer can pull and inspect.
 *
 * Scope is deliberately tight — no post-mortem capture, no per-reboot
 * classification, no JSONL. The conversation moved from "diagnose the
 * reboot cause" to "make sure the logs from before the crash are
 * always harvestable", and this service is the single piece of
 * machinery that needs to be running for that to work.
 *
 * Started from [DumbDownApp.onCreate] via [startIfEnabled]. Runs as
 * START_STICKY so the OS restarts it after low-memory kills — exactly
 * the case where we'd otherwise lose the recent log lines we want.
 *
 * Gated by [BuildConfig.REBOOT_LOGGING_ENABLED] (compile-time) plus
 * [RebootLoggingStore.enabled] (runtime opt-in). Either off → the
 * service satisfies the foreground-service contract and immediately
 * stops, so production builds compile the class but never act.
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
        if (!BuildConfig.REBOOT_LOGGING_ENABLED) {
            startForegroundPlaceholderThenStop()
            return
        }
        store = RebootLoggingStore(this)
        if (!store.enabled) {
            startForegroundPlaceholderThenStop()
            return
        }

        startForeground(RebootLoggingConfig.NOTIFICATION_ID, buildNotification())

        val privateRoot = DiagPaths.privateDiagDir(this)
        val mirrorRoot = DiagPaths.mirrorDiagDir(this)
        rollingLogcat = RollingLogcatTail(privateRoot, mirrorRoot).also { it.start() }

        Log.i(tag, "reboot logging service started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!BuildConfig.REBOOT_LOGGING_ENABLED ||
            !this::store.isInitialized ||
            !store.enabled
        ) {
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

        // User-facing copy. Marco needs to know what this notification
        // is for every time he opens the shade, so this is intentionally
        // plain-English ("Storing logs for testing…") rather than the
        // engineer-oriented "Reboot diagnostics running" / "Recording
        // rolling logcat" we shipped to the launcher's own developers.
        // Title fits the 240×320 cover-display row; subtitle expands to
        // a one-sentence explanation when the row is tapped.
        return NotificationCompat.Builder(this, RebootLoggingConfig.NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(iconRes)
            .setContentTitle("Storing logs for testing…")
            .setContentText("Beta build — helping us find a fix for the random restarts.")
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    "Beta build only. The launcher is keeping a 24-hour rolling log of " +
                        "system events on this device so we can find the cause of the " +
                        "random restarts. The log stays on this phone unless you send it " +
                        "to us — nothing is uploaded automatically."
                )
            )
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
            "Storing logs for testing",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Beta build only. Keeps a 24-hour rolling log on this device " +
                "to help find the cause of the random restarts."
            setShowBadge(false)
            enableLights(false)
            enableVibration(false)
        }
        nm.createNotificationChannel(channel)
    }

    companion object {
        /**
         * Start the service when the compile-time gate is on.
         *
         * On the diag beta build (BuildConfig.REBOOT_LOGGING_ENABLED =
         * true), shipping the APK to Marco via the -beta.0 channel IS
         * the consent step — we're not asking a regular production user
         * mid-flight whether they want diagnostics. So when the build
         * flag is on and the runtime opt-in has never been touched
         * (enabledSinceMs == 0L), we auto-flip the opt-in here on
         * first launch and continue. After that, the runtime flag is
         * the authoritative on/off switch — flipping it false via adb
         * stops collection without uninstalling.
         */
        fun startIfEnabled(context: Context) {
            if (!BuildConfig.REBOOT_LOGGING_ENABLED) return
            val store = RebootLoggingStore(context)
            if (!store.enabled) {
                if (store.enabledSinceMs == 0L) {
                    // First launch on this device after install. Auto-
                    // enable so Marco doesn't have to touch adb.
                    store.enabled = true
                    store.enabledSinceMs = System.currentTimeMillis()
                } else {
                    // Explicit kill switch — user (or we, remotely) has
                    // flipped this off. Honour it.
                    return
                }
            }
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
