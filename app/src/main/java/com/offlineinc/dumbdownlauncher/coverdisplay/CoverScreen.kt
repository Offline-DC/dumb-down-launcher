package com.offlineinc.dumbdownlauncher.coverdisplay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import com.offlineinc.dumbdownlauncher.notifications.NotificationStore
import kotlinx.coroutines.delay
import java.util.concurrent.Executor

/**
 * Drives live state (clock, battery, notifications, overlay logic) then
 * hands everything off to [CoverScreenContent] for rendering.
 */
@Composable
fun CoverScreen() {
    val context = LocalContext.current

    // Clock — driven by system broadcasts so it never drifts when the
    // process is suspended. ACTION_TIME_TICK fires every minute while the
    // screen is on; TIME_CHANGED / TIMEZONE_CHANGED cover manual adjustments.
    var tick by remember { mutableIntStateOf(0) }

    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) { tick++ }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        context.registerReceiver(receiver, filter)
        onDispose { try { context.unregisterReceiver(receiver) } catch (_: Exception) {} }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) tick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val locale = remember { java.util.Locale.getDefault() }
    val is24   = remember { android.text.format.DateFormat.is24HourFormat(context) }
    val dateFmt = remember { java.text.SimpleDateFormat("EEE, MMM d", locale) }
    val timeFmt = remember { java.text.SimpleDateFormat(if (is24) "HH:mm" else "h:mm", locale) }
    val timeText = remember(tick) { timeFmt.format(java.util.Date()) }
    val dateText = remember(tick) { dateFmt.format(java.util.Date()).lowercase() }

    // Battery
    var batteryPct by remember { mutableStateOf<Int?>(null) }
    var isCharging by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val level  = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale  = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                if (level >= 0) batteryPct = (level * 100f / scale).toInt()
                isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                             status == BatteryManager.BATTERY_STATUS_FULL
            }
        }
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        onDispose { try { context.unregisterReceiver(receiver) } catch (_: Exception) {} }
    }

    // Ringer mode (silent / vibrate / normal)
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    var ringerMode by remember { mutableIntStateOf(audioManager.ringerMode) }
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                ringerMode = audioManager.ringerMode
            }
        }
        val filter = IntentFilter(AudioManager.RINGER_MODE_CHANGED_ACTION)
        context.registerReceiver(receiver, filter)
        onDispose { try { context.unregisterReceiver(receiver) } catch (_: Exception) {} }
    }

    // Telephony call state — used to gate the call overlay so a stale CATEGORY_CALL
    // notification (left behind by the dialer across a reboot or crash) can't pin
    // the cover display to an "incoming call" overlay for a contact that isn't
    // actually calling. We only ever show the overlay while the radio reports a
    // real RINGING or OFFHOOK state.
    val telephonyManager = remember {
        context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
    }
    var callState by remember { mutableIntStateOf(TelephonyManager.CALL_STATE_IDLE) }
    DisposableEffect(Unit) {
        // Inline executor: state updates are trivial and we want them on the same
        // thread that owns the Compose state to avoid an extra hop.
        val executor = Executor { it.run() }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val cb = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
                override fun onCallStateChanged(state: Int) { callState = state }
            }
            try {
                telephonyManager.registerTelephonyCallback(executor, cb)
            } catch (_: SecurityException) {
                // READ_PHONE_STATE not granted — fall through; overlay simply
                // won't be gated, matching pre-patch behaviour for this user.
            }
            onDispose {
                try { telephonyManager.unregisterTelephonyCallback(cb) } catch (_: Exception) {}
            }
        } else {
            @Suppress("DEPRECATION")
            val listener = object : PhoneStateListener() {
                @Deprecated("Pre-S API; replaced by TelephonyCallback above")
                override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                    callState = state
                }
            }
            try {
                @Suppress("DEPRECATION")
                telephonyManager.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
            } catch (_: SecurityException) {}
            onDispose {
                try {
                    @Suppress("DEPRECATION")
                    telephonyManager.listen(listener, PhoneStateListener.LISTEN_NONE)
                } catch (_: Exception) {}
            }
        }
    }
    val phoneRingingOrOffHook =
        callState == TelephonyManager.CALL_STATE_RINGING ||
        callState == TelephonyManager.CALL_STATE_OFFHOOK

    // Notifications
    val allNotifs by NotificationStore.items().observeAsState(emptyList())
    val sessionStart  = remember { System.currentTimeMillis() }
    val hasNewNotifs  = remember(allNotifs) { allNotifs.any { it.postTime > sessionStart } }

    // Track the active call by notification key so we keep the overlay when Android
    // transitions the same notification from CATEGORY_CALL (ringing) to ongoing-call
    // (category changes, but key stays the same). Missed-call notifications arrive
    // as separate entries with a different key and won't match.
    //
    // The call-state gate above is the safety net: even if a stale call notification
    // is in the store, we refuse to surface it as an overlay unless the telephony
    // layer agrees there is a real call. When callState returns to IDLE we drop
    // effectiveCallNotif to null, which lets the existing clear branch run.
    var trackedCallKey by remember { mutableStateOf<String?>(null) }
    val callNotif = remember(allNotifs, phoneRingingOrOffHook) {
        if (!phoneRingingOrOffHook) null
        else allNotifs.firstOrNull { it.isCall() }
    }
    val effectiveCallNotif = remember(allNotifs, trackedCallKey, phoneRingingOrOffHook) {
        if (!phoneRingingOrOffHook) null
        else callNotif ?: allNotifs.firstOrNull { it.key == trackedCallKey }
    }

    val latestMsg = remember(allNotifs) {
        allNotifs.filter { it.isMessage() }.maxByOrNull { it.postTime }
    }

    // Overlay state machine
    var overlay by remember { mutableStateOf<OverlayState?>(null) }
    LaunchedEffect(effectiveCallNotif?.key) {
        if (effectiveCallNotif != null) {
            trackedCallKey = effectiveCallNotif.key
            val (number, location) = parseCallTitle(effectiveCallNotif.title)
            overlay = OverlayState(OverlayKind.CALL, "incoming call", number, location)
            val contactName = lookupContactName(context, number)
            if (contactName != null) {
                overlay = OverlayState(OverlayKind.CALL, "incoming call", contactName)
            }
        } else {
            trackedCallKey = null
            if (overlay?.kind == OverlayKind.CALL) {
                delay(3_000)
                if (overlay?.kind == OverlayKind.CALL) overlay = null
            }
        }
    }
    var lastShownMsgTime by remember { mutableStateOf(0L) }
    LaunchedEffect(latestMsg?.postTime) {
        val msg = latestMsg
        if (msg == null) {
            if (overlay?.kind == OverlayKind.MESSAGE) overlay = null
            return@LaunchedEffect
        }
        if (msg.postTime <= lastShownMsgTime) return@LaunchedEffect
        lastShownMsgTime = msg.postTime
        if (overlay?.kind != OverlayKind.CALL) {
            overlay = OverlayState(OverlayKind.MESSAGE, "new message", msg.title)
            delay(6_000)
            if (overlay?.kind == OverlayKind.MESSAGE) overlay = null
        }
    }

    CoverScreenContent(
        timeText   = timeText,
        dateText   = dateText,
        batteryPct = batteryPct,
        isCharging = isCharging,
        badgeCount = allNotifs.size,
        hasNew     = hasNewNotifs,
        overlay    = overlay,
        ringerMode = ringerMode,
    )
}

// ── Previews ──────────────────────────────────────────────────────────────────
// Duplicate of CoverScreenContent previews — kept here so you can preview
// the output without leaving this file while editing state logic.

@Preview(name = "Idle", showBackground = true, backgroundColor = 0xFF000000, device = "spec:width=128px,height=128px,dpi=213")
@Composable
private fun PreviewIdle() = CoverScreenContent(
    timeText   = "9:41",
    dateText   = "mon, mar 9",
    batteryPct = 87,
    isCharging = false,
    badgeCount = 2,
    hasNew     = true,
    overlay    = null,
)

@Preview(name = "Charging", showBackground = true, backgroundColor = 0xFF000000, device = "spec:width=128px,height=128px,dpi=213")
@Composable
private fun PreviewCharging() = CoverScreenContent(
    timeText   = "9:41",
    dateText   = "mon, mar 9",
    batteryPct = 63,
    isCharging = true,
    badgeCount = 0,
    hasNew     = false,
    overlay    = null,
)

@Preview(name = "New message", showBackground = true, backgroundColor = 0xFF000000, device = "spec:width=128px,height=128px,dpi=213")
@Composable
private fun PreviewMessage() = CoverScreenContent(
    timeText   = "9:41",
    dateText   = "mon, mar 9",
    batteryPct = 87,
    isCharging = false,
    badgeCount = 3,
    hasNew     = true,
    overlay    = OverlayState(OverlayKind.MESSAGE, "new message", ""),
)

@Preview(name = "Incoming call", showBackground = true, backgroundColor = 0xFF000000, device = "spec:width=128px,height=128px,dpi=213")
@Composable
private fun PreviewCall() = CoverScreenContent(
    timeText   = "9:41",
    dateText   = "mon, mar 9",
    batteryPct = 52,
    isCharging = false,
    badgeCount = 1,
    hasNew     = false,
    overlay    = OverlayState(OverlayKind.CALL, "incoming call", "Alex"),
)

@Preview(name = "Low battery", showBackground = true, backgroundColor = 0xFF000000, device = "spec:width=128px,height=128px,dpi=213")
@Composable
private fun PreviewLowBattery() = CoverScreenContent(
    timeText   = "9:41",
    dateText   = "mon, mar 9",
    batteryPct = 7,
    isCharging = false,
    badgeCount = 0,
    hasNew     = false,
    overlay    = null,
)
