package com.offlineinc.dumbdownlauncher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.offlineinc.dumbdownlauncher.ui.theme.DumbTheme

/**
 * Compact DND status badge for the homepage.
 * Shows a mute icon + "MUTE" label when DND is enabled.
 * Hidden when DND is off.
 */
@Composable
fun DndStatusIndicator(
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    if (!enabled) return

    Row(
        modifier = modifier
            .background(
                color = DumbTheme.Colors.Yellow.copy(alpha = 0.85f),
                shape = RoundedCornerShape(6.dp),
            )
            .padding(horizontal = 6.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.foundation.Image(
            painter = rememberVectorPainter(Icons.Filled.VolumeOff),
            contentDescription = "Muted",
            modifier = Modifier.size(14.dp),
            colorFilter = ColorFilter.tint(DumbTheme.Colors.Black),
        )
        Spacer(Modifier.width(3.dp))
        BasicText(
            text = "MUTE",
            style = TextStyle(
                fontFamily = DumbTheme.BioRhyme,
                fontSize = 10.sp,
                color = DumbTheme.Colors.Black,
            ),
        )
    }
}

// ── Previews ──────────────────────────────────────────────────────────────────

@Preview(
    name = "DND – Enabled",
    showBackground = true,
    backgroundColor = 0xFF000000,
)
@Composable
private fun PreviewDndEnabled() {
    Box(modifier = Modifier.padding(8.dp)) {
        DndStatusIndicator(enabled = true)
    }
}

@Preview(
    name = "DND – Disabled",
    showBackground = true,
    backgroundColor = 0xFF000000,
)
@Composable
private fun PreviewDndDisabled() {
    Box(modifier = Modifier.padding(8.dp)) {
        DndStatusIndicator(enabled = false)
    }
}
