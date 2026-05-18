package com.offlineinc.dumbdownlauncher.storage

import android.content.Context
import android.os.Environment
import android.os.StatFs
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.offlineinc.dumbdownlauncher.ui.SoftKeyBar
import com.offlineinc.dumbdownlauncher.ui.theme.DumbTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * "Free up space" screen — short list of suggestions, one button per
 * category. NOT a comprehensive storage manager. Each row hides when
 * its size is under [SIZE_HIDE_THRESHOLD_BYTES] so users only see
 * cleanups worth doing.
 *
 * UX:
 *  - D-pad up/down moves selection between rows.
 *  - D-pad center / Enter on the selected row opens an in-screen
 *    confirmation. Soft-left = yes (clear), soft-right = no (back to list).
 *  - Back / soft-right on the list dismisses the screen.
 *
 * State machine:
 *  - [Mode.LOADING]   — initial size queries are running
 *  - [Mode.EMPTY]     — every row was under threshold, show "all clear"
 *  - [Mode.LIST]      — at least one row, user can navigate / activate
 *  - [Mode.CONFIRM]   — user is on the confirmation step for [pendingRow]
 *  - [Mode.CLEARING]  — root call in flight (shows the row's spinner)
 */

/** Bytes below which a row hides itself — keeps the list focused. */
private const val SIZE_HIDE_THRESHOLD_BYTES: Long = 10L * 1024L * 1024L  // 10 MB

private enum class Mode { LOADING, EMPTY, LIST, CONFIRM, CLEARING }

private data class Suggestion(
    val target: StorageCleanupOps.Target,
    val title: String,
    val description: String,
    val sizeBytes: Long,
    val lastRunAtMs: Long,
    val confirmBody: String,
)

@Composable
fun FreeUpSpaceScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    var mode by remember { mutableStateOf(Mode.LOADING) }
    val rows: SnapshotStateList<Suggestion> = remember { mutableStateListOf() }
    var selectedIdx by remember { mutableIntStateOf(0) }
    var pendingRowIdx by remember { mutableIntStateOf(0) }
    var lastFreedDisplay by remember { mutableStateOf("") }
    var freeBytes by remember { mutableStateOf(0L) }
    var totalBytes by remember { mutableStateOf(0L) }

    val focusRequester = remember { FocusRequester() }
    val coroutineScope = rememberCoroutineScope()
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    // Initial load. Runs once on screen open.
    LaunchedEffect(Unit) {
        loadEverything(context, rows, onTotals = { f, t ->
            freeBytes = f; totalBytes = t
        })
        mode = if (rows.isEmpty()) Mode.EMPTY else Mode.LIST
        selectedIdx = 0
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DumbTheme.Colors.Black)
            .focusRequester(focusRequester)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (mode) {
                    Mode.LIST -> handleListKey(
                        key = event.key,
                        rows = rows,
                        selectedIdx = selectedIdx,
                        setSelectedIdx = { selectedIdx = it },
                        onActivate = {
                            pendingRowIdx = selectedIdx
                            mode = Mode.CONFIRM
                        },
                        onBack = onBack,
                    )
                    Mode.CONFIRM -> handleConfirmKey(
                        key = event.key,
                        onYes = {
                            mode = Mode.CLEARING
                            // Kick off the clear on IO, then refresh.
                            // Tied to the composition's scope so leaving the
                            // screen mid-clear cancels cleanly.
                            coroutineScope.launch {
                                val target = rows[pendingRowIdx].target
                                val result = withContext(Dispatchers.IO) {
                                    runCleanupFor(context, target)
                                }
                                lastFreedDisplay = result.bytesFreedDisplay
                                // Refresh sizes + last-run timestamps.
                                rows.clear()
                                loadEverything(context, rows, onTotals = { f, t ->
                                    freeBytes = f; totalBytes = t
                                })
                                mode = if (rows.isEmpty()) Mode.EMPTY else Mode.LIST
                                selectedIdx = selectedIdx.coerceIn(0, rows.lastIndex.coerceAtLeast(0))
                            }
                        },
                        onNo = {
                            mode = Mode.LIST
                        },
                    )
                    Mode.LOADING, Mode.EMPTY, Mode.CLEARING -> {
                        // Allow back to exit from any state.
                        if (event.key == Key.Back || event.key == Key.SoftRight) {
                            onBack(); true
                        } else false
                    }
                }
            }
            .focusable(),
    ) {
        Spacer(Modifier.height(DumbTheme.Spacing.ScreenPaddingV))

        BasicText(
            text = "free up space",
            style = DumbTheme.Text.PageTitle.copy(color = DumbTheme.Colors.Yellow),
            modifier = Modifier.padding(horizontal = DumbTheme.Spacing.ScreenPaddingH),
        )

        Spacer(Modifier.height(4.dp))

        BasicText(
            text = freeOfTotalLine(freeBytes, totalBytes),
            style = DumbTheme.Text.Subtitle,
            modifier = Modifier.padding(horizontal = DumbTheme.Spacing.ScreenPaddingH),
        )

        if (lastFreedDisplay.isNotEmpty()) {
            Spacer(Modifier.height(2.dp))
            BasicText(
                text = "just cleared $lastFreedDisplay",
                style = DumbTheme.Text.Hint.copy(color = DumbTheme.Colors.Green),
                modifier = Modifier.padding(horizontal = DumbTheme.Spacing.ScreenPaddingH),
            )
        }

        Spacer(Modifier.height(DumbTheme.Spacing.SectionGap))

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (mode) {
                Mode.LOADING -> CenterMessage("checking storage…")
                Mode.EMPTY -> CenterMessage(
                    "you're all set —\nnothing worth clearing right now",
                )
                Mode.LIST, Mode.CLEARING -> RowList(rows, selectedIdx)
                Mode.CONFIRM -> {
                    val row = rows.getOrNull(pendingRowIdx)
                    if (row != null) ConfirmPanel(row)
                    else CenterMessage("…")
                }
            }
        }

        BasicText(
            text = "media files in messages are cleared automatically every night. your messages are kept.",
            style = DumbTheme.Text.Hint,
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = DumbTheme.Spacing.ScreenPaddingH,
                    vertical = 8.dp,
                ),
        )

        SoftKeyBar(
            leftLabel = when (mode) {
                Mode.CONFIRM -> "yes, clear"
                Mode.LIST -> "clear"
                else -> null
            },
            rightLabel = when (mode) {
                Mode.CONFIRM -> "cancel"
                else -> "back"
            },
        )
    }
}

// ── Sub-composables ──────────────────────────────────────────────────────

@Composable
private fun CenterMessage(text: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(
            text = text,
            style = DumbTheme.Text.Body.copy(
                color = DumbTheme.Colors.Gray,
                textAlign = TextAlign.Center,
            ),
            modifier = Modifier.padding(horizontal = DumbTheme.Spacing.ScreenPaddingH),
        )
    }
}

@Composable
private fun RowList(rows: List<Suggestion>, selectedIdx: Int) {
    Column(Modifier.fillMaxSize()) {
        rows.forEachIndexed { idx, row ->
            val selected = idx == selectedIdx
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        if (selected) DumbTheme.Colors.Yellow.copy(alpha = 0.18f)
                        else Color.Transparent
                    )
                    .padding(
                        horizontal = DumbTheme.Spacing.ScreenPaddingH,
                        vertical = 10.dp,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    BasicText(
                        text = row.title,
                        style = DumbTheme.Text.Body.copy(
                            color = if (selected) DumbTheme.Colors.Yellow
                            else DumbTheme.Colors.White,
                        ),
                    )
                    if (row.description.isNotEmpty()) {
                        BasicText(
                            text = row.description,
                            style = DumbTheme.Text.Hint,
                        )
                    }
                }
                BasicText(
                    text = humanBytes(row.sizeBytes),
                    style = DumbTheme.Text.Body.copy(
                        color = DumbTheme.Colors.Gray,
                    ),
                )
            }
        }
    }
}

@Composable
private fun ConfirmPanel(row: Suggestion) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = DumbTheme.Spacing.ScreenPaddingH),
        verticalArrangement = Arrangement.Center,
    ) {
        BasicText(
            text = "clear ${row.title.lowercase()}?",
            style = DumbTheme.Text.Title.copy(color = DumbTheme.Colors.Yellow),
        )
        Spacer(Modifier.height(12.dp))
        BasicText(
            text = row.confirmBody,
            style = DumbTheme.Text.Body,
        )
        Spacer(Modifier.height(12.dp))
        BasicText(
            text = "frees ${humanBytes(row.sizeBytes)}",
            style = DumbTheme.Text.Subtitle,
        )
    }
}

// ── Key handling ─────────────────────────────────────────────────────────

private fun handleListKey(
    key: Key,
    rows: List<Suggestion>,
    selectedIdx: Int,
    setSelectedIdx: (Int) -> Unit,
    onActivate: () -> Unit,
    onBack: () -> Unit,
): Boolean {
    if (rows.isEmpty()) {
        if (key == Key.Back || key == Key.SoftRight) {
            onBack(); return true
        }
        return false
    }
    return when (key) {
        Key.DirectionUp, Key.NumPad2 -> {
            setSelectedIdx((selectedIdx - 1 + rows.size) % rows.size)
            true
        }
        Key.DirectionDown, Key.NumPad8 -> {
            setSelectedIdx((selectedIdx + 1) % rows.size)
            true
        }
        Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.SoftLeft -> {
            onActivate(); true
        }
        Key.Back, Key.SoftRight -> { onBack(); true }
        else -> false
    }
}

private fun handleConfirmKey(
    key: Key,
    onYes: () -> Unit,
    onNo: () -> Unit,
): Boolean = when (key) {
    Key.SoftLeft, Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> { onYes(); true }
    Key.SoftRight, Key.Back -> { onNo(); true }
    else -> false
}

// ── Data loading ─────────────────────────────────────────────────────────

private suspend fun loadEverything(
    context: Context,
    rows: SnapshotStateList<Suggestion>,
    onTotals: (free: Long, total: Long) -> Unit,
) = coroutineScope {
    val ctx = context.applicationContext

    // Free/total are local FS calls — fast, no root, no thread switch.
    val data = Environment.getDataDirectory()
    val stat = StatFs(data.absolutePath)
    val total = stat.blockCountLong * stat.blockSizeLong
    val free = stat.availableBlocksLong * stat.blockSizeLong
    onTotals(free, total)

    // Size queries run sequentially on IO. The result is a flat
    // mutable map populated with explicit put() calls — earlier shapes
    // (parallel async, `to`-infix list, sequential mapOf {...}) all
    // tripped the same Kotlin resolution failure where call-site
    // references to StorageCleanupOps members were marked unresolved
    // despite the symbols being clearly present in the source.
    val sizesMap: MutableMap<StorageCleanupOps.Target, Long> =
        mutableMapOf()
    withContext(Dispatchers.IO) {
        sizesMap[StorageCleanupOps.Target.ANTENNAPOD] =
            StorageCleanupOps.antennaPodSizeBytes()
        sizesMap[StorageCleanupOps.Target.SPOTIFY_OFFLINE] =
            StorageCleanupOps.spotifyOfflineSizeBytes()
        sizesMap[StorageCleanupOps.Target.APPLE_MUSIC_OFFLINE] =
            StorageCleanupOps.appleMusicOfflineSizeBytes()
        sizesMap[StorageCleanupOps.Target.APP_CACHES] =
            StorageCleanupOps.totalAppCachesSizeBytes()
    }

    // Build rows in display order. Hide anything under the threshold so
    // the screen stays a list of *useful* actions.
    val ordered = listOf(
        StorageCleanupOps.Target.SPOTIFY_OFFLINE,
        StorageCleanupOps.Target.ANTENNAPOD,
        StorageCleanupOps.Target.APPLE_MUSIC_OFFLINE,
        StorageCleanupOps.Target.APP_CACHES,
    )
    rows.clear()
    for (t in ordered) {
        val size = sizesMap[t] ?: 0L
        if (size < SIZE_HIDE_THRESHOLD_BYTES) continue
        val lastMs = StorageCleanupOps.lastRunAtMs(ctx, t)
        rows.add(rowFor(t, size, lastMs))
    }
}

private fun rowFor(
    target: StorageCleanupOps.Target,
    sizeBytes: Long,
    lastRunAtMs: Long,
): Suggestion = when (target) {
    StorageCleanupOps.Target.SPOTIFY_OFFLINE -> Suggestion(
        target = target,
        title = "spotify offline",
        description = lastClearedSubtitle(lastRunAtMs)
            ?: "downloaded songs",
        sizeBytes = sizeBytes,
        lastRunAtMs = lastRunAtMs,
        confirmBody = "your downloaded songs will be removed. you'll need wi-fi to download them again for offline listening.",
    )
    StorageCleanupOps.Target.ANTENNAPOD -> Suggestion(
        target = target,
        title = "podcasts",
        description = lastClearedSubtitle(lastRunAtMs)
            ?: "downloaded episodes",
        sizeBytes = sizeBytes,
        lastRunAtMs = lastRunAtMs,
        confirmBody = "your downloaded podcast episodes will be removed. they'll re-download next time you play them.",
    )
    StorageCleanupOps.Target.APPLE_MUSIC_OFFLINE -> Suggestion(
        target = target,
        title = "apple music offline",
        description = lastClearedSubtitle(lastRunAtMs)
            ?: "downloaded songs",
        sizeBytes = sizeBytes,
        lastRunAtMs = lastRunAtMs,
        confirmBody = "your downloaded songs will be removed. you'll need wi-fi to download them again for offline listening.",
    )
    StorageCleanupOps.Target.APP_CACHES -> Suggestion(
        target = target,
        title = "app caches",
        description = lastClearedSubtitle(lastRunAtMs)
            ?: "thumbnails, previews, podcast episodes",
        sizeBytes = sizeBytes,
        lastRunAtMs = lastRunAtMs,
        confirmBody = "caches across all apps will be cleared. this includes any downloaded podcast episodes. apps will rebuild thumbnails and previews as you use them.",
    )
}

private fun runCleanupFor(
    context: Context,
    target: StorageCleanupOps.Target,
): StorageCleanupOps.ClearResult = when (target) {
    StorageCleanupOps.Target.APP_CACHES -> StorageCleanupOps.trimAppCaches(context)
    StorageCleanupOps.Target.ANTENNAPOD -> StorageCleanupOps.clearAntennaPodEpisodes(context)
    StorageCleanupOps.Target.SPOTIFY_OFFLINE -> StorageCleanupOps.clearSpotifyOffline(context)
    StorageCleanupOps.Target.APPLE_MUSIC_OFFLINE -> StorageCleanupOps.clearAppleMusicOffline(context)
}

// ── Formatting helpers ───────────────────────────────────────────────────

private fun freeOfTotalLine(free: Long, total: Long): String {
    if (total <= 0) return ""
    return "${humanBytes(free)} free of ${humanBytes(total)}"
}

private fun humanBytes(bytes: Long): String {
    if (bytes < 1024) return "${bytes} B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.0f KB".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024) return "%.0f MB".format(mb)
    val gb = mb / 1024.0
    return "%.1f GB".format(gb)
}

private fun lastClearedSubtitle(lastRunAtMs: Long): String? {
    if (lastRunAtMs <= 0) return null
    val deltaMs = System.currentTimeMillis() - lastRunAtMs
    if (deltaMs < 0) return null
    val h = TimeUnit.MILLISECONDS.toHours(deltaMs)
    if (h < 1) return "last cleared just now"
    if (h < 24) return "last cleared ${h}h ago"
    val d = TimeUnit.MILLISECONDS.toDays(deltaMs)
    return "last cleared ${d}d ago"
}
