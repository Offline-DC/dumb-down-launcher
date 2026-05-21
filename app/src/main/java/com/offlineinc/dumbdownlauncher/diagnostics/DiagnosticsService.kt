package com.offlineinc.dumbdownlauncher.diagnostics

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.offlineinc.dumbdownlauncher.BuildConfig
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Foreground service that drives the battery-diagnostics collection loop:
 *
 *   1. Once per minute, sample BatteryManager + ACTION_BATTERY_CHANGED and
 *      append a row to samples.jsonl. Also re-emits as an event so a
 *      single-file timeline view is possible.
 *   2. Subscribes to screen/power/doze broadcasts; appends each transition
 *      to events.jsonl.
 *   3. Hands off to PrivilegedDumpsysScheduler which runs `su -c dumpsys …`
 *      once per hour and on every screen-on / screen-off transition.
 *
 * Started from DiagnosticsActivity when the user opts in, and re-started
 * automatically from DumbDownApp.onCreate at process start if the opt-in
 * flag is set. Stopped from DiagnosticsActivity or by toggling the flag
 * off.
 *
 * Gated by BuildConfig.DIAGNOSTICS_ENABLED — production builds compile the
 * class but the entry points refuse to start the service.
 */
class DiagnosticsService : Service() {

    private val tag = "DiagnosticsService"

    private lateinit var store: DiagnosticsStore
    private lateinit var eventsWriter: JsonlWriter
    private lateinit var samplesWriter: JsonlWriter
    private lateinit var dumpsysScheduler: PrivilegedDumpsysScheduler
    private var lidSensorReader: LidSensorReader? = null

    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "DiagnosticsService-sched").apply { isDaemon = true }
    }
    private var samplingHandle: ScheduledFuture<*>? = null

    @Volatile private var lastScreenState: String = "unknown"
    @Volatile private var lastLidState: String = "unknown"

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()

        // Foreground-service contract (Android 8+): once the OS has
        // promoted us via startForegroundService(), we MUST call
        // startForeground() within ~5s or it throws RemoteServiceException
        // and kills the process. The early-exit paths below (build flag
        // off, opt-in toggled off) used to return without ever calling
        // startForeground — which crashed any time the OS auto-restarted
        // a STICKY-killed service while the user happened to have
        // diagnostics disabled. Satisfy the contract first, then bail.
        if (!BuildConfig.DIAGNOSTICS_ENABLED) {
            startForegroundPlaceholderThenStop()
            return
        }
        store = DiagnosticsStore(this)
        if (!store.enabled) {
            startForegroundPlaceholderThenStop()
            return
        }

        val privateRoot = DiagnosticsPaths.privateDiagDir(this)
        val mirrorRoot = DiagnosticsPaths.mirrorDiagDir(this)

        eventsWriter = JsonlWriter(privateRoot, mirrorRoot, basename = "events")
        samplesWriter = JsonlWriter(privateRoot, mirrorRoot, basename = "samples")
        dumpsysScheduler = PrivilegedDumpsysScheduler(
            context = this,
            diagRoot = privateRoot,
            mirrorRoot = mirrorRoot,
            store = store,
            eventsWriter = eventsWriter,
        )

        startForeground(DiagnosticsConfig.NOTIFICATION_ID, buildNotification())

        // Capture the initial screen state so first broadcast can diff against it.
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        lastScreenState = if (pm?.isInteractive == true) "on" else "off"

        // Write a session_start event so the post-processor can detect a clean window.
        appendEvent(
            type = "session_start",
            source = "launcher",
            payload = mapOf(
                "build_fingerprint" to Build.FINGERPRINT,
                "manufacturer" to Build.MANUFACTURER,
                "model" to Build.MODEL,
                "android_release" to Build.VERSION.RELEASE,
                "sdk_int" to Build.VERSION.SDK_INT,
                "launcher_version" to BuildConfig.VERSION_NAME,
                "schema_version" to DiagnosticsConfig.SCHEMA_VERSION,
                "diagnostics_build" to "diag-${BuildConfig.VERSION_NAME}",
                "cohort" to (store.cohort ?: "unset"),
            ),
        )

        registerBroadcastReceivers()
        scheduleSampling()
        dumpsysScheduler.start(executor)
        startLidSensorReader()

        Log.i(tag, "Diagnostics service started; session=${store.captureSessionId}")
    }

    // ── Lid sensor reader ────────────────────────────────────────────────
    //
    // On TCL 4058W the hall sensor is wired to /dev/input/event3 with a
    // vendor-defined keycode (0x00fc) — see DiagnosticsConfig and
    // LidSensorReader for the full rationale. We tail the input device
    // via `su -c getevent` rather than SensorManager because the lid is
    // not exposed as a Sensor or SW_LID input switch on this hardware.

    private fun startLidSensorReader() {
        val reader = LidSensorReader(
            onLidStateChanged = { newState ->
                lastLidState = newState
            },
            onLidEvent = { type, payload ->
                appendEvent(type = type, source = "launcher", payload = payload)
            },
        )
        lidSensorReader = reader
        reader.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!BuildConfig.DIAGNOSTICS_ENABLED || !this::store.isInitialized || !store.enabled) {
            stopSelf()
            return START_NOT_STICKY
        }
        // Sticky so the OS restarts us after low-memory kills — exactly the
        // case we want to keep observing.
        return START_STICKY
    }

    override fun onDestroy() {
        try { samplingHandle?.cancel(false) } catch (_: Throwable) {}
        try { unregisterReceiver(broadcastReceiver) } catch (_: Throwable) {}
        try { lidSensorReader?.stop() } catch (_: Throwable) {}
        lidSensorReader = null
        if (this::dumpsysScheduler.isInitialized) dumpsysScheduler.stop()
        if (this::eventsWriter.isInitialized) {
            appendEvent("session_end", "launcher", emptyMap())
            eventsWriter.close()
        }
        if (this::samplesWriter.isInitialized) samplesWriter.close()
        executor.shutdownNow()
        super.onDestroy()
    }

    // ── Sampling loop ────────────────────────────────────────────────────

    private fun scheduleSampling() {
        samplingHandle = executor.scheduleAtFixedRate(
            ::sampleOnce,
            0L,
            DiagnosticsConfig.BATTERY_SAMPLE_INTERVAL_MS,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun sampleOnce() {
        try {
            val line = BatterySampler.sampleAsJson(
                context = this,
                sessionId = store.captureSessionId,
                screenStateHint = lastScreenState,
                lidStateHint = lastLidState,
            )
            samplesWriter.append(line)
        } catch (t: Throwable) {
            Log.w(tag, "sample failed", t)
        }
    }

    // ── Event broadcasts ─────────────────────────────────────────────────

    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val action = intent?.action ?: return
            val type = when (action) {
                Intent.ACTION_SCREEN_ON -> { lastScreenState = "on"; "screen_on" }
                Intent.ACTION_SCREEN_OFF -> { lastScreenState = "off"; "screen_off" }
                Intent.ACTION_USER_PRESENT -> "user_present"
                Intent.ACTION_POWER_CONNECTED -> "power_connected"
                Intent.ACTION_POWER_DISCONNECTED -> "power_disconnected"
                Intent.ACTION_BATTERY_LOW -> "battery_low"
                Intent.ACTION_BATTERY_OKAY -> "battery_okay"
                PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED -> "doze_changed"
                PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> "power_save_changed"
                else -> "broadcast"
            }
            val payload = mutableMapOf<String, Any?>("action" to action)
            if (action == PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED) {
                val pm = context?.getSystemService(Context.POWER_SERVICE) as? PowerManager
                payload["is_device_idle_mode"] = pm?.isDeviceIdleMode
            }
            if (action == PowerManager.ACTION_POWER_SAVE_MODE_CHANGED) {
                val pm = context?.getSystemService(Context.POWER_SERVICE) as? PowerManager
                payload["is_power_save_mode"] = pm?.isPowerSaveMode
            }

            appendEvent(type = type, source = "system", payload = payload)

            // Trigger an immediate dumpsys snapshot on every screen transition so
            // we capture the system state at the boundary, per the plan §1.4.
            if (action == Intent.ACTION_SCREEN_ON || action == Intent.ACTION_SCREEN_OFF) {
                dumpsysScheduler.requestSnapshotAsync("screen_transition")
            }
        }
    }

    private fun registerBroadcastReceivers() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_BATTERY_LOW)
            addAction(Intent.ACTION_BATTERY_OKAY)
            addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        }
        registerReceiver(broadcastReceiver, filter)
    }

    private fun appendEvent(type: String, source: String, payload: Map<String, Any?>) {
        if (!this::eventsWriter.isInitialized) return
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        eventsWriter.append(
            DiagEvents.encode(
                type = type,
                source = source,
                tsMs = System.currentTimeMillis(),
                monotonicMs = SystemClock.elapsedRealtime(),
                sessionId = store.captureSessionId,
                screenState = lastScreenState,
                lidState = lastLidState,
                charging = null,
                batteryLevelPct = null,
                inDoze = try { pm?.isDeviceIdleMode } catch (_: Throwable) { null },
                payload = payload,
            )
        )
    }

    // ── Foreground-service notification ───────────────────────────────────

    /**
     * Satisfy the Android 8+ foreground-service contract in the early-exit
     * paths of [onCreate] (build flag off, opt-in off). We have to call
     * [startForeground] before [stopSelf] or the OS throws
     * RemoteServiceException. The notification is removed immediately
     * because [stopSelf] tears the service down — so the user never
     * actually sees it.
     */
    private fun startForegroundPlaceholderThenStop() {
        try {
            startForeground(DiagnosticsConfig.NOTIFICATION_ID, buildNotification())
        } catch (t: Throwable) {
            Log.w(tag, "placeholder startForeground failed", t)
        }
        stopSelf()
    }

    private fun buildNotification(): Notification {
        ensureChannel()
        // Reuse the existing launcher icon — DumbDownLauncher always has this
        // resource available, and we want zero visual surprise on the device.
        val iconRes = try {
            resources.getIdentifier("ic_launcher_round", "mipmap", packageName)
                .takeIf { it != 0 } ?: android.R.drawable.stat_sys_data_bluetooth
        } catch (_: Throwable) { android.R.drawable.stat_sys_data_bluetooth }

        return NotificationCompat.Builder(this, DiagnosticsConfig.NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(iconRes)
            .setContentTitle("Battery diagnostics running")
            .setContentText("Collecting logs for battery investigation")
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(DiagnosticsConfig.NOTIFICATION_CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            DiagnosticsConfig.NOTIFICATION_CHANNEL_ID,
            "Battery diagnostics",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Background collection of battery samples for the beta diagnostics build."
            setShowBadge(false)
            enableLights(false)
            enableVibration(false)
        }
        nm.createNotificationChannel(channel)
    }

    companion object {
        /**
         * Start the service if diagnostics is enabled. Safe to call from any
         * launcher entry point — no-op when the build flag or opt-in is off.
         */
        fun startIfEnabled(context: Context) {
            if (!BuildConfig.DIAGNOSTICS_ENABLED) return
            val store = DiagnosticsStore(context)
            if (!store.enabled) return
            val intent = Intent(context, DiagnosticsService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, DiagnosticsService::class.java)
            context.stopService(intent)
        }
    }
}
