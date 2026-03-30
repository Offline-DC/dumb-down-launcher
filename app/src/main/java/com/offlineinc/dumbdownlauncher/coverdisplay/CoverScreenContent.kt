package com.offlineinc.dumbdownlauncher.coverdisplay

import android.media.AudioManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.offlineinc.dumbdownlauncher.ui.RingerModeIndicator

/**
 * Pure stateless layout. Positions each cover display component and delegates
 * all styling to the individual component files. Edit a component file and its
 * own previews reflect the change instantly.
 */
@Composable
internal fun CoverScreenContent(
    timeText:   String,
    dateText:   String,
    batteryPct: Int?,
    isCharging: Boolean,
    badgeCount: Int,
    hasNew:     Boolean,
    overlay:    OverlayState?,
    ringerMode: Int = AudioManager.RINGER_MODE_NORMAL,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Black)
    ) {
        CoverClock(
            timeText = timeText,
            dateText = dateText,
            modifier = Modifier.align(Alignment.Center),
        )

        CoverNotificationOverlay(overlay = overlay)

        if (overlay?.kind != OverlayKind.CALL) {
            CoverBattery(
                batteryPct = batteryPct,
                isCharging = isCharging,
                modifier   = Modifier
                    .align(Alignment.TopEnd)
                    .padding(3.dp),
            )

            CoverNotificationBadge(
                badgeCount = badgeCount,
                hasNew     = hasNew,
                modifier   = Modifier
                    .align(Alignment.TopStart)
                    .padding(3.dp),
            )

            RingerModeIndicator(
                ringerMode = ringerMode,
                iconSize   = 10.dp,
                modifier   = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(3.dp),
            )
        }
    }
}

// ── Previews ──────────────────────────────────────────────────────────────────
// Full-screen previews of the assembled layout.
// See individual component files for focused component previews.

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
