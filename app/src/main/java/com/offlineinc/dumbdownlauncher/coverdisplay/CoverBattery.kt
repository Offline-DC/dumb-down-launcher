package com.offlineinc.dumbdownlauncher.coverdisplay

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.offlineinc.dumbdownlauncher.ui.theme.DumbTheme

@Composable
internal fun CoverBattery(
    batteryPct: Int?,
    isCharging: Boolean,
    modifier:   Modifier = Modifier,
) {
    val font = DumbTheme.BioRhyme
    Row(
        modifier          = modifier,
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
                color      = if (batteryPct != null && batteryPct < 10) Color(0xFFE53935) else Gray,
            ),
        )
    }
}

// ── Previews ──────────────────────────────────────────────────────────────────

@Preview(name = "Battery – normal", showBackground = true, backgroundColor = 0xFF000000, device = "spec:width=128px,height=128px,dpi=213")
@Composable
private fun PreviewBatteryNormal() {
    Box(Modifier.fillMaxSize().background(Black), contentAlignment = Alignment.TopEnd) {
        CoverBattery(batteryPct = 87, isCharging = false)
    }
}

@Preview(name = "Battery – charging", showBackground = true, backgroundColor = 0xFF000000, device = "spec:width=128px,height=128px,dpi=213")
@Composable
private fun PreviewBatteryCharging() {
    Box(Modifier.fillMaxSize().background(Black), contentAlignment = Alignment.TopEnd) {
        CoverBattery(batteryPct = 63, isCharging = true)
    }
}

@Preview(name = "Battery – low", showBackground = true, backgroundColor = 0xFF000000, device = "spec:width=128px,height=128px,dpi=213")
@Composable
private fun PreviewBatteryLow() {
    Box(Modifier.fillMaxSize().background(Black), contentAlignment = Alignment.TopEnd) {
        CoverBattery(batteryPct = 7, isCharging = false)
    }
}
