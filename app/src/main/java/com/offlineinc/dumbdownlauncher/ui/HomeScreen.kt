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
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.offlineinc.dumbdownlauncher.ui.theme.DumbTheme
import kotlinx.coroutines.Dispatchers
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
    // Instead of a coroutine delay loop (which drifts when the process is
    // suspended), we listen to the system ACTION_TIME_TICK broadcast that
    // Android delivers reliably every minute while the screen is on.
    // We also listen for manual time/timezone changes and refresh on
    // lifecycle resume so the display is never stale.
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
            val imageBitmap = remember(bmp) { bmp.asImageBitmap() }
            Image(
                bitmap = imageBitmap,
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

        // ── Clock (upper area) ───────────────────────────────────────────
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
        }

        // ── Mute badge — top-right corner, only visible when muted ───────
        DndStatusIndicator(
            enabled = messagesMuted,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 6.dp, end = 6.dp),
        )

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
                text = "notifs",
                modifier = Modifier.weight(1f),
                style = TextStyle(
                    color = DumbTheme.Colors.Yellow.copy(alpha = 0.65f),
                    fontSize = 12.sp,
                    fontFamily = DumbTheme.BioRhyme,
                    textAlign = TextAlign.Start,
                ),
            )
            BasicText(
                text = "apps",
                modifier = Modifier.weight(1f),
                style = TextStyle(
                    color = DumbTheme.Colors.Yellow.copy(alpha = 0.65f),
                    fontSize = 12.sp,
                    fontFamily = DumbTheme.BioRhyme,
                    textAlign = TextAlign.Center,
                ),
            )
            BasicText(
                text = "all",
                modifier = Modifier.weight(1f),
                style = TextStyle(
                    color = DumbTheme.Colors.Yellow.copy(alpha = 0.65f),
                    fontSize = 12.sp,
                    fontFamily = DumbTheme.BioRhyme,
                    textAlign = TextAlign.End,
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
                text = "notifs",
                modifier = Modifier.weight(1f),
                style = TextStyle(
                    color = DumbTheme.Colors.Yellow.copy(alpha = 0.65f),
                    fontSize = 12.sp,
                    fontFamily = DumbTheme.BioRhyme,
                    textAlign = TextAlign.Start,
                ),
            )
            BasicText(
                text = "apps",
                modifier = Modifier.weight(1f),
                style = TextStyle(
                    color = DumbTheme.Colors.Yellow.copy(alpha = 0.65f),
                    fontSize = 12.sp,
                    fontFamily = DumbTheme.BioRhyme,
                    textAlign = TextAlign.Center,
                ),
            )
            BasicText(
                text = "all",
                modifier = Modifier.weight(1f),
                style = TextStyle(
                    color = DumbTheme.Colors.Yellow.copy(alpha = 0.65f),
                    fontSize = 12.sp,
                    fontFamily = DumbTheme.BioRhyme,
                    textAlign = TextAlign.End,
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
                text = "notifs",
                modifier = Modifier.weight(1f),
                style = TextStyle(
                    color = DumbTheme.Colors.Yellow.copy(alpha = 0.65f),
                    fontSize = 12.sp,
                    fontFamily = DumbTheme.BioRhyme,
                    textAlign = TextAlign.Start,
                ),
            )
            BasicText(
                text = "apps",
                modifier = Modifier.weight(1f),
                style = TextStyle(
                    color = DumbTheme.Colors.Yellow.copy(alpha = 0.65f),
                    fontSize = 12.sp,
                    fontFamily = DumbTheme.BioRhyme,
                    textAlign = TextAlign.Center,
                ),
            )
            BasicText(
                text = "all",
                modifier = Modifier.weight(1f),
                style = TextStyle(
                    color = DumbTheme.Colors.Yellow.copy(alpha = 0.65f),
                    fontSize = 12.sp,
                    fontFamily = DumbTheme.BioRhyme,
                    textAlign = TextAlign.End,
                ),
            )
        }
    }
}
