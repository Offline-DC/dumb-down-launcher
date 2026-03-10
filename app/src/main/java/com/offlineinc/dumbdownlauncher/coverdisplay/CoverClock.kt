package com.offlineinc.dumbdownlauncher.coverdisplay

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

@Composable
internal fun CoverClock(
    timeText: String,
    dateText: String,
    modifier: Modifier = Modifier,
) {
    val font = DumbTheme.BioRhyme
    Column(
        modifier            = modifier,
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
                color         = White,
                letterSpacing = 0.5.sp,
            ),
        )
    }
}

// ── Previews ──────────────────────────────────────────────────────────────────

@Preview(name = "Clock", showBackground = true, backgroundColor = 0xFF000000, device = "spec:width=128px,height=128px,dpi=213")
@Composable
private fun PreviewClock() {
    Box(
        modifier         = Modifier.fillMaxSize().background(Black),
        contentAlignment = Alignment.Center,
    ) {
        CoverClock(timeText = "9:41", dateText = "mon, mar 9")
    }
}

@Preview(name = "Clock – 24h", showBackground = true, backgroundColor = 0xFF000000, device = "spec:width=128px,height=128px,dpi=213")
@Composable
private fun PreviewClock24h() {
    Box(
        modifier         = Modifier.fillMaxSize().background(Black),
        contentAlignment = Alignment.Center,
    ) {
        CoverClock(timeText = "21:41", dateText = "mon, mar 9")
    }
}
