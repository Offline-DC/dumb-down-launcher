package com.offlineinc.dumbdownlauncher.ui

import android.app.WallpaperManager
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.offlineinc.dumbdownlauncher.ui.theme.DumbTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Main homepage screen — the first thing users see when the launcher opens.
 *
 * Layout (240×320 @ density 110):
 *   Top-right:  DND status indicator
 *   Center:     Large clock + date (hero element)
 *   Bottom:     Center hint + soft key labels
 *
 * Key handling:
 *   D-pad Center / Enter  → onOpenAppsGrid (3×3 main apps)
 *   Soft-left  (Key.Menu) → onOpenNotifications
 *   Soft-right (Key.B)    → onOpenAllApps
 *   D-pad Up/Down/L/R     → onDpadShortcut (TCL key shortcuts)
 *   # key                  → DND toggle (handled at Activity level, not here)
 *   Back                   → no-op (this IS home)
 */
@Composable
fun HomeScreen(
    messagesMuted: Boolean,
    onOpenAppsGrid: () -> Unit,
    onOpenNotifications: () -> Unit,
    onOpenAllApps: () -> Unit,
    onDpadDirection: (DpadDirection) -> Unit,
    wallpaperRefreshKey: Int = 0,
) {
    val context = LocalContext.current
    val focusRequester = remember { FocusRequester() }

    // ── Clock state ──────────────────────────────────────────────────────
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

    // ── Wallpaper ────────────────────────────────────────────────────────
    // Re-keyed on wallpaperRefreshKey so a new bitmap is fetched whenever
    // the Activity signals that the wallpaper may have changed (e.g. onResume).
    var wallpaperBitmap by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(wallpaperRefreshKey) {
        wallpaperBitmap = withContext(Dispatchers.IO) {
            try {
                val wm = WallpaperManager.getInstance(context)
                // peekDrawable doesn't need READ_EXTERNAL_STORAGE on some builds
                val drawable = wm.peekDrawable() ?: wm.drawable
                drawable?.toBitmap()
            } catch (_: Exception) {
                // Fallback: no wallpaper → solid black
                null
            }
        }
    }

    // ── Focus ────────────────────────────────────────────────────────────
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DumbTheme.Colors.Black)
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.Enter, Key.NumPadEnter, Key.DirectionCenter -> {
                        onOpenAppsGrid()
                        true
                    }
                    Key.Menu -> {
                        onOpenNotifications()
                        true
                    }
                    Key.B -> {
                        onOpenAllApps()
                        true
                    }
                    Key.DirectionUp -> {
                        onDpadDirection(DpadDirection.UP)
                        true
                    }
                    Key.DirectionDown -> {
                        onDpadDirection(DpadDirection.DOWN)
                        true
                    }
                    Key.DirectionLeft -> {
                        onDpadDirection(DpadDirection.LEFT)
                        true
                    }
                    Key.DirectionRight -> {
                        onDpadDirection(DpadDirection.RIGHT)
                        true
                    }
                    else -> false
                }
            }
    ) {
        // ── Wallpaper background ─────────────────────────────────────────
        wallpaperBitmap?.let { bmp ->
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            // Dark overlay for text readability
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.25f))
            )
        }

        // ── Clock + mute badge (upper area) ─────────────────────────────
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            HomeClockDisplay(
                timeText = timeText,
                dateText = dateText,
            )
            // Mute badge centered below date — only visible when muted
            DndStatusIndicator(
                enabled = messagesMuted,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        // ── Bottom bar: notifications | all apps ─────────────────────
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black)
                .padding(horizontal = 10.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText(
                text = "notifications",
                style = TextStyle(
                    color = DumbTheme.Colors.Yellow.copy(alpha = 0.65f),
                    fontSize = 12.sp,
                    fontFamily = DumbTheme.BioRhyme,
                ),
            )
            BasicText(
                text = "all apps",
                style = TextStyle(
                    color = DumbTheme.Colors.Yellow.copy(alpha = 0.65f),
                    fontSize = 12.sp,
                    fontFamily = DumbTheme.BioRhyme,
                ),
            )
        }
    }
}

enum class DpadDirection {
    UP, DOWN, LEFT, RIGHT
}

// ── Previews ──────────────────────────────────────────────────────────────────

@Preview(
    name = "Homepage – DND off",
    showBackground = true,
    backgroundColor = 0xFF000000,
    device = "spec:width=240px,height=320px,dpi=110"
)
@Composable
private fun PreviewHomeScreen() {
    // Static preview — no wallpaper, simulated clock
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DumbTheme.Colors.Black)
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            HomeClockDisplay(timeText = "9:41", dateText = "sun, mar 15")
            DndStatusIndicator(enabled = false)
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black)
                .padding(horizontal = 10.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText(
                text = "notifications",
                style = TextStyle(
                    color = DumbTheme.Colors.Yellow.copy(alpha = 0.65f),
                    fontSize = 12.sp,
                    fontFamily = DumbTheme.BioRhyme,
                ),
            )
            BasicText(
                text = "all apps",
                style = TextStyle(
                    color = DumbTheme.Colors.Yellow.copy(alpha = 0.65f),
                    fontSize = 12.sp,
                    fontFamily = DumbTheme.BioRhyme,
                ),
            )
        }
    }
}

@Preview(
    name = "Homepage – DND on",
    showBackground = true,
    backgroundColor = 0xFF000000,
    device = "spec:width=240px,height=320px,dpi=110"
)
@Composable
private fun PreviewHomeScreenDndOn() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DumbTheme.Colors.Black)
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            HomeClockDisplay(timeText = "2:30", dateText = "mon, mar 16")
            DndStatusIndicator(enabled = true, modifier = Modifier.padding(top = 6.dp))
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black)
                .padding(horizontal = 10.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText(
                text = "notifications",
                style = TextStyle(
                    color = DumbTheme.Colors.Yellow.copy(alpha = 0.65f),
                    fontSize = 12.sp,
                    fontFamily = DumbTheme.BioRhyme,
                ),
            )
            BasicText(
                text = "all apps",
                style = TextStyle(
                    color = DumbTheme.Colors.Yellow.copy(alpha = 0.65f),
                    fontSize = 12.sp,
                    fontFamily = DumbTheme.BioRhyme,
                ),
            )
        }
    }
}
