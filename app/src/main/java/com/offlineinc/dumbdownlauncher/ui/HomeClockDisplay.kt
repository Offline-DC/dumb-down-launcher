package com.offlineinc.dumbdownlauncher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.sp
import com.offlineinc.dumbdownlauncher.ui.theme.DumbTheme

/**
 * Clock + date display for the main homepage.
 * Scaled up from CoverClock (which targets the 128×128 sub-display)
 * to fit the 240×320 main screen at density 110.
 */
@Composable
fun HomeClockDisplay(
    timeText: String,
    dateText: String,
    modifier: Modifier = Modifier,
) {
    val font = DumbTheme.BioRhyme
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BasicText(
            text = timeText,
            style = TextStyle(
                fontFamily    = font,
                fontSize      = 46.sp,
                color         = DumbTheme.Colors.White,
                letterSpacing = (-1).sp,
                textAlign     = TextAlign.Center,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        BasicText(
            text = dateText,
            style = TextStyle(
                fontFamily    = font,
                fontSize      = 14.sp,
                color         = DumbTheme.Colors.White.copy(alpha = 0.7f),
                letterSpacing = 0.5.sp,
                textAlign     = TextAlign.Center,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// ── Previews ──────────────────────────────────────────────────────────────────

@Preview(
    name = "Home Clock – 12h",
    showBackground = true,
    backgroundColor = 0xFF000000,
    device = "spec:width=240px,height=320px,dpi=110"
)
@Composable
private fun PreviewHomeClock12h() {
    Box(
        modifier         = Modifier.fillMaxSize().background(DumbTheme.Colors.Black),
        contentAlignment = Alignment.Center,
    ) {
        HomeClockDisplay(timeText = "9:41", dateText = "mon, mar 15")
    }
}

@Preview(
    name = "Home Clock – 24h",
    showBackground = true,
    backgroundColor = 0xFF000000,
    device = "spec:width=240px,height=320px,dpi=110"
)
@Composable
private fun PreviewHomeClock24h() {
    Box(
        modifier         = Modifier.fillMaxSize().background(DumbTheme.Colors.Black),
        contentAlignment = Alignment.Center,
    ) {
        HomeClockDisplay(timeText = "21:41", dateText = "sun, mar 15")
    }
}
