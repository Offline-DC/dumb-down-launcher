package com.offlineinc.dumbdownlauncher.coverdisplay

import android.app.Notification
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.provider.ContactsContract
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.MailOutline
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.offlineinc.dumbdownlauncher.notifications.NotificationStore
import com.offlineinc.dumbdownlauncher.notifications.model.NotificationItem
import com.offlineinc.dumbdownlauncher.ui.theme.DumbTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ── Call / message detection ──────────────────────────────────────────────────

// Primary: match by standard Android notification category (works on all dialers).
// Fallback package list covers edge cases where category isn't set.
private fun NotificationItem.isCall(): Boolean =
    category == Notification.CATEGORY_CALL || packageName in CALL_PACKAGES

private val CALL_PACKAGES = setOf(
    "com.android.server.telecom",
    "com.google.android.dialer",
    "com.samsung.android.incallui",
    "com.android.phone",
)

private val MSG_PACKAGES = setOf(
    "com.google.android.apps.messaging",
    "com.android.mms",
    "com.samsung.android.messaging",
    "com.whatsapp",
    "com.whatsapp.w4b",
    "com.facebook.orca",
    "org.telegram.messenger",
    "org.telegram.plus",
    "com.discord",
    "com.snapchat.android",
    "com.instagram.android",
    "com.twitter.android",
)

// ── Overlay state ─────────────────────────────────────────────────────────────

private enum class OverlayKind { CALL, MESSAGE }

private data class OverlayState(
    val kind:  OverlayKind,
    val line1: String,   // "incoming call" or "new message"
    val line2: String,   // caller name (only shown for calls)
)

// ── Contact lookup ────────────────────────────────────────────────────────────

/**
 * Looks up a display name for [number] via ContactsContract.
 * Returns null if not found or permission is denied — caller should fall back
 * to whatever the notification already has in its title.
 */
private suspend fun lookupContactName(context: Context, number: String): String? =
    withContext(Dispatchers.IO) {
        try {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(number),
            )
            context.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
                null, null, null,
            )?.use { cursor ->
                if (cursor.moveToFirst())
                    cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.PhoneLookup.DISPLAY_NAME))
                else null
            }
        } catch (_: Exception) {
            null
        }
    }

// ── Colors ────────────────────────────────────────────────────────────────────

private val Black  = DumbTheme.Colors.Black
private val White  = DumbTheme.Colors.White
private val Yellow = DumbTheme.Colors.Yellow
private val Gray   = DumbTheme.Colors.Gray

// ── State container ───────────────────────────────────────────────────────────

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
        val dateFmt = SimpleDateFormat("EEE, MMM d", Locale.getDefault())
        while (true) {
            val now = Date()
            val timeFmt = if (android.text.format.DateFormat.is24HourFormat(context))
                SimpleDateFormat("HH:mm", Locale.getDefault())
            else
                SimpleDateFormat("h:mm", Locale.getDefault())
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
    val callNotif: NotificationItem? = remember(allNotifs) {
        allNotifs.firstOrNull { it.isCall() }
    }
    val latestMsg: NotificationItem? = remember(allNotifs) {
        allNotifs.filter { it.packageName in MSG_PACKAGES }.maxByOrNull { it.postTime }
    }

    // Overlay state machine
    var overlay by remember { mutableStateOf<OverlayState?>(null) }
    LaunchedEffect(callNotif?.key) {
        if (callNotif != null) {
            // Show the number immediately, then swap in the contact name if found
            overlay = OverlayState(OverlayKind.CALL, "incoming call", callNotif.title)
            val contactName = lookupContactName(context, callNotif.title)
            if (contactName != null) {
                overlay = OverlayState(OverlayKind.CALL, "incoming call", contactName)
            }
        } else if (overlay?.kind == OverlayKind.CALL) {
            // Debounce before clearing: Android replaces the ringing notification with
            // an ongoing-call notification, causing a brief null gap. Wait long enough
            // for the replacement to arrive; if a new call key shows up the coroutine
            // is cancelled and the overlay stays put.
            delay(20_000)
            if (overlay?.kind == OverlayKind.CALL) overlay = null
        }
    }
    var lastShownMsgKey by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(latestMsg?.key) {
        val msg = latestMsg ?: return@LaunchedEffect
        if (msg.key == lastShownMsgKey) return@LaunchedEffect
        lastShownMsgKey = msg.key
        if (overlay?.kind != OverlayKind.CALL) {
            overlay = OverlayState(OverlayKind.MESSAGE, "new message", msg.title)
            delay(12_000)
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

// ── UI ────────────────────────────────────────────────────────────────────────

/**
 * Pure stateless UI. All styling lives here — change a font size and every
 * @Preview below reflects it instantly without rerunning live state.
 */
@Composable
private fun CoverScreenContent(
    timeText:   String,
    dateText:   String,
    batteryPct: Int?,
    isCharging: Boolean,
    badgeCount: Int,
    hasNew:     Boolean,
    overlay:    OverlayState?,
) {
    val font      = DumbTheme.BioRhyme
    val callColor = Yellow.copy(alpha = 0.55f)

    // ── Overlay spacing — tweak these two values to adjust icon↔label gap ──
    val overlayIconToLabel = 2.dp   // gap between icon and first line of text
    val overlayLabelToSub  = 2.dp   // gap between label and caller name (calls only)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Black)
    ) {

        // Time + date — centre
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BasicText(
                text = timeText,
                style = TextStyle(
                    fontFamily    = font,
                    fontSize      = 32.sp,
                    color         = White,
                    letterSpacing = (-0.5).sp,
                    textAlign     = TextAlign.Center,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            BasicText(
                text = dateText,
                style = TextStyle(
                    fontFamily    = font,
                    fontSize      = 8.sp,
                    color         = Gray,
                    letterSpacing = 0.5.sp,
                )
            )
        }

        // Notification overlay — full screen, fades in/out
        AnimatedVisibility(
            visible  = overlay != null,
            modifier = Modifier.fillMaxSize(),
            enter    = fadeIn(),
            exit     = fadeOut(),
        ) {
            val o = overlay
            if (o != null) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Black),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    // ── Overlay icon size — change this one value ──
                    val overlayIconSize = 36.dp
                    Icon(
                        imageVector = if (o.kind == OverlayKind.CALL) Icons.Rounded.Call else Icons.Rounded.MailOutline,
                        contentDescription = null,
                        tint     = if (o.kind == OverlayKind.CALL) callColor else White,
                        modifier = Modifier.size(overlayIconSize),
                    )
                    Spacer(Modifier.height(overlayIconToLabel))
                    BasicText(
                        text  = o.line1,
                        style = TextStyle(
                            fontFamily = font,
                            fontSize   = 8.sp,
                            color      = if (o.kind == OverlayKind.CALL) callColor else White,
                            textAlign  = TextAlign.Center,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (o.kind == OverlayKind.CALL) {
                        Spacer(Modifier.height(overlayLabelToSub))
                        BasicText(
                            text     = o.line2,
                            style    = TextStyle(
                                fontFamily = font,
                                fontSize   = 7.sp,
                                color      = Gray,
                                textAlign  = TextAlign.Center,
                            ),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }

        // Battery — top end (with optional charging bolt)
        Row(
            modifier          = Modifier
                .align(Alignment.TopEnd)
                .padding(3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (isCharging) {
                Icon(
                    imageVector        = Icons.Rounded.Bolt,
                    contentDescription = null,
                    tint               = White,
                    modifier           = Modifier.size(8.dp),
                )
            }
            BasicText(
                text  = batteryPct?.let { "$it%" } ?: "\u2014",
                style = TextStyle(
                    fontFamily = font,
                    fontSize   = 6.sp,
                    color      = if (batteryPct != null && batteryPct!! < 10) Color(0xFFE53935) else Gray,
                ),
            )
        }

        // Notification badge — top start
        AnimatedVisibility(
            visible  = badgeCount > 0,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(3.dp),
            enter = fadeIn(),
            exit  = fadeOut(),
        ) {
            Box(
                modifier         = Modifier
                    .requiredSize(12.dp)
                    .background(
                        color = if (hasNew) Yellow else Color(0xFF444444),
                        shape = CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    text  = if (badgeCount > 9) "9+" else badgeCount.toString(),
                    style = TextStyle(
                        fontFamily    = font,
                        fontSize      = 5.sp,
                        lineHeight    = 5.sp,
                        color         = if (hasNew) Black else Gray,
                        textAlign     = TextAlign.Center,
                        platformStyle = PlatformTextStyle(includeFontPadding = false),
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

// ── Previews ──────────────────────────────────────────────────────────────────
// All previews call CoverScreenContent directly with plain strings.
// Changing any style in CoverScreenContent above is immediately reflected here.

@Preview(name = "Idle", showBackground = true, backgroundColor = 0xFF000000, widthDp = 128, heightDp = 128)
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

@Preview(name = "Charging", showBackground = true, backgroundColor = 0xFF000000, widthDp = 128, heightDp = 128)
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

@Preview(name = "New message", showBackground = true, backgroundColor = 0xFF000000, widthDp = 128, heightDp = 128)
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

@Preview(name = "Incoming call", showBackground = true, backgroundColor = 0xFF000000, widthDp = 128, heightDp = 128)
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

@Preview(name = "Low battery", showBackground = true, backgroundColor = 0xFF000000, widthDp = 128, heightDp = 128)
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
