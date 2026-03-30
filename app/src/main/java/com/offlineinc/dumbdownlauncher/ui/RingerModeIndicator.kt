package com.offlineinc.dumbdownlauncher.ui

import android.media.AudioManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.offlineinc.dumbdownlauncher.ui.theme.DumbTheme

/**
 * Subtle ringer-mode indicator — always visible.
 *
 *   Normal  → speaker icon (VolumeUp)
 *   Vibrate → squiggly vibration lines
 *   Silent  → speaker with X (VolumeOff)
 *
 * [ringerMode] should be one of [AudioManager.RINGER_MODE_NORMAL],
 * [AudioManager.RINGER_MODE_SILENT], or [AudioManager.RINGER_MODE_VIBRATE].
 *
 * [iconSize] lets callers scale down for tight spaces (e.g. 128×128 cover).
 */
@Composable
fun RingerModeIndicator(
    ringerMode: Int,
    modifier: Modifier = Modifier,
    iconSize: Dp = 12.dp,
) {
    val icon = when (ringerMode) {
        AudioManager.RINGER_MODE_SILENT  -> Icons.Filled.VolumeOff
        AudioManager.RINGER_MODE_VIBRATE -> Icons.Filled.Vibration
        else                             -> Icons.Filled.VolumeUp
    }
    val description = when (ringerMode) {
        AudioManager.RINGER_MODE_SILENT  -> "Silent"
        AudioManager.RINGER_MODE_VIBRATE -> "Vibrate"
        else                             -> "Volume"
    }

    Image(
        painter = rememberVectorPainter(icon),
        contentDescription = description,
        modifier = modifier.size(iconSize),
        colorFilter = ColorFilter.tint(
            DumbTheme.Colors.White.copy(alpha = 0.45f)
        ),
    )
}

// ── Previews ─────────────────────────────────────────────────────────────────

@Preview(name = "Normal (volume)", showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun PreviewNormal() {
    Box(Modifier.padding(8.dp)) {
        RingerModeIndicator(ringerMode = AudioManager.RINGER_MODE_NORMAL)
    }
}

@Preview(name = "Vibrate", showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun PreviewVibrate() {
    Box(Modifier.padding(8.dp)) {
        RingerModeIndicator(ringerMode = AudioManager.RINGER_MODE_VIBRATE)
    }
}

@Preview(name = "Silent", showBackground = true, backgroundColor = 0xFF000000)
@Composable
private fun PreviewSilent() {
    Box(Modifier.padding(8.dp)) {
        RingerModeIndicator(ringerMode = AudioManager.RINGER_MODE_SILENT)
    }
}
