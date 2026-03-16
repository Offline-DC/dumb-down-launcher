package com.offlineinc.dumbdownlauncher.ui

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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.offlineinc.dumbdownlauncher.model.AppItem
import com.offlineinc.dumbdownlauncher.ui.theme.DumbTheme

/**
 * 3×3 grid screen for the 9 main apps.
 *
 * D-pad navigation:
 *   Up/Down    → move by ±3 (rows), clamped to 0–8
 *   Left/Right → move by ±1 within the same row (no wrapping across rows)
 *   Center     → launch selected app
 *   Back       → return to homepage
 *   Menu       → notifications
 *   B          → all apps
 *
 * Layout targets 240×320 @ density 110.
 */
@Composable
fun MainAppGridScreen(
    items: List<AppItem>,
    onActivate: (AppItem) -> Unit,
    onBack: () -> Unit,
    /** Home screen wallpaper bitmap — displayed behind the grid with a dark overlay. */
    wallpaperBitmap: Bitmap? = null,
) {
    val focusRequester = remember { FocusRequester() }
    var selectedIndex by remember { mutableIntStateOf(0) }

    LaunchedEffect(items.size) {
        selectedIndex = selectedIndex.coerceIn(0, (items.lastIndex).coerceAtLeast(0))
    }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Box(modifier = Modifier.fillMaxSize()) {
        // ── Wallpaper background (darker overlay than home screen) ────────
        if (wallpaperBitmap != null) {
            Image(
                bitmap = wallpaperBitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            // Heavier dark overlay (70%) so grid cells stay readable
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.70f))
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(DumbTheme.Colors.Black)
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 6.dp, vertical = 6.dp)
                .focusRequester(focusRequester)
                .focusable()
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.DirectionUp -> {
                            if (selectedIndex >= 3) selectedIndex -= 3
                            true
                        }
                        Key.DirectionDown -> {
                            if (selectedIndex + 3 <= 8 && selectedIndex + 3 < items.size) {
                                selectedIndex += 3
                            }
                            true
                        }
                        Key.DirectionLeft -> {
                            if (selectedIndex % 3 > 0) selectedIndex -= 1
                            true
                        }
                        Key.DirectionRight -> {
                            if (selectedIndex % 3 < 2 && selectedIndex + 1 < items.size) {
                                selectedIndex += 1
                            }
                            true
                        }
                        Key.Enter, Key.NumPadEnter, Key.DirectionCenter -> {
                            if (items.isNotEmpty()) onActivate(items[selectedIndex])
                            true
                        }
                        Key.Back -> {
                            onBack()
                            true
                        }
                        else -> false
                    }
                },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // ── Grid rows ───────────────────────────────────────────────
            // 3 rows × 3 columns. Each row gets equal weight.
            val rowCount = 3
            val colCount = 3

            for (row in 0 until rowCount) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    for (col in 0 until colCount) {
                        val index = row * colCount + col
                        if (index < items.size) {
                            MainAppGridCell(
                                item = items[index],
                                selected = (index == selectedIndex),
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
    }
}

// ── Previews ──────────────────────────────────────────────────────────────────

private fun previewGridItems(): List<AppItem> = listOf(
    previewAppItem("smart txt", GOOGLE_MESSAGES_PKG),
    previewAppItem("whatsapp", "com.whatsapp"),
    previewAppItem("sms", "com.android.mms"),
    previewAppItem("contacts", "com.android.contacts"),
    previewAppItem("call history", "com.android.dialer"),
    previewAppItem("settings", "com.android.settings"),
    previewAppItem("maps lite", "com.google.android.apps.mapslite"),
    previewAppItem("camera", "com.tcl.camera"),
    previewAppItem("uber", "com.ubercab.uberlite"),
)

private const val GOOGLE_MESSAGES_PKG = "__GOOGLE_MESSAGES__"

@Preview(
    name = "App Grid – Center selected",
    showBackground = true,
    backgroundColor = 0xFF000000,
    device = "spec:width=240px,height=320px,dpi=110"
)
@Composable
private fun PreviewGridCenter() {
    MainAppGridScreen(
        items = previewGridItems(),
        onActivate = {},
        onBack = {},
    )
}

@Preview(
    name = "App Grid – Corner selected",
    showBackground = true,
    backgroundColor = 0xFF000000,
    device = "spec:width=240px,height=320px,dpi=110"
)
@Composable
private fun PreviewGridCorner() {
    // This preview won't dynamically show a different selection since
    // selectedIndex is internal state, but it validates layout at device spec.
    MainAppGridScreen(
        items = previewGridItems(),
        onActivate = {},
        onBack = {},
    )
}
