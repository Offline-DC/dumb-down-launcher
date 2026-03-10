package com.offlineinc.dumbdownlauncher.coverdisplay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import com.offlineinc.dumbdownlauncher.notifications.NotificationStore
import kotlinx.coroutines.delay

/**
 * Drives live state (clock, battery, notifications, overlay logic) then
 * hands everything off to [CoverScreenContent] for rendering.
 */
@Composable
fun CoverScreen() {
    val context = LocalContext.current

    // Clock
    var timeText by remember { mutableStateOf("") }
    var dateText by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        val dateFmt = java.text.SimpleDateFormat("EEE, MMM d", java.util.Locale.getDefault())
        while (true) {
            val now = java.util.Date()
            val timeFmt = if (android.text.format.DateFormat.is24HourFormat(context))
                java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
            else
                java.text.SimpleDateFormat("h:mm", java.util.Locale.getDefault())
            timeText = timeFmt.format(now)
            dateText = dateFmt.format(now).lowercase()
            delay(1_000)
        }
    }

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

    // Notifications
    val allNotifs by NotificationStore.items().observeAsState(emptyList())
    val sessionStart  = remember { System.currentTimeMillis() }
    val hasNewNotifs  = remember(allNotifs) { allNotifs.any { it.postTime > sessionStart } }

    // Track the active call by notification key so we keep the overlay when Android
    // transitions the same notification from CATEGORY_CALL (ringing) to ongoing-call
    // (category changes, but key stays the same). Missed-call notifications arrive
    // as separate entries with a different key and won't match.
    var trackedCallKey by remember { mutableStateOf<String?>(null) }
    val callNotif = remember(allNotifs) { allNotifs.firstOrNull { it.isCall() } }
    val effectiveCallNotif = remember(allNotifs, trackedCallKey) {
        callNotif ?: allNotifs.firstOrNull { it.key == trackedCallKey }
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
