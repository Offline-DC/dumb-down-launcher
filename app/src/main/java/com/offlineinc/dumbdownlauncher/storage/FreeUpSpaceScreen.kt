package com.offlineinc.dumbdownlauncher.storage

import android.content.ActivityNotFoundException
import android.content.Context
import android.os.Environment
import android.os.StatFs
import android.util.Log
import android.widget.Toast
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.unit.sp
import com.offlineinc.dumbdownlauncher.ui.SoftKeyBar
import com.offlineinc.dumbdownlauncher.ui.components.DumbSpinner
import com.offlineinc.dumbdownlauncher.ui.theme.DumbTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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

/**
 * Bytes below which a row hides itself — keeps the list focused on
 * cleanups that actually matter on a low-storage flip phone. 200 MB
 * is the user-set "this is worth doing" cutoff: anything smaller
 * isn't going to move the needle on a 4–8 GB device and just clutters
 * the screen. If every bucket is under the threshold the screen falls
 * through to Mode.EMPTY ("you're all set — nothing worth clearing").
 */
private const val SIZE_HIDE_THRESHOLD_BYTES: Long = 200L * 1024L * 1024L  // 200 MB

private enum class Mode { LOADING, EMPTY, LIST, CONFIRM, CLEARING }

private data class Suggestion(
    val target: StorageCleanupOps.Target,
    val title: String,
    val description: String,
    val sizeBytes: Long,
    val confirmBody: String,
    /**
     * Soft-key label shown on the confirm panel for this row. Defaults
     * to "yes, clear" because every row was a direct file-wipe action
     * until Spotify — where wiping files outside the app makes Spotify
     * silently re-download them (its internal DB still says they're
     * downloaded). The Spotify row routes the "yes" press to launching
     * Spotify itself so the user can use its built-in
     * Settings → Storage → Remove all downloads, which keeps Spotify's
     * state in sync. The label change makes the SoftKeyBar accurate.
     */
    val confirmActionLabel: String = "yes, clear",
)

@Composable
fun FreeUpSpaceScreen(onBack: () -> Unit) {
    val context = LocalContext.current

    var mode by remember { mutableStateOf(Mode.LOADING) }
    val rows: SnapshotStateList<Suggestion> = remember { mutableStateListOf() }
    var selectedIdx by remember { mutableIntStateOf(0) }
    var pendingRowIdx by remember { mutableIntStateOf(0) }
    var freeBytes by remember { mutableStateOf(0L) }
    var totalBytes by remember { mutableStateOf(0L) }

    // Info-page overlay state. Toggled by the d-pad center / OK key in
    // LIST mode (soft-key labeled "info"). Stays inside this composable
    // rather than spinning up a second activity so the row list
    // survives a peek-and-return.
    var showInfo by remember { mutableStateOf(false) }

    val focusRequester = remember { FocusRequester() }
    val coroutineScope = rememberCoroutineScope()

    // Initial load — positioned ABOVE the `if (showInfo) return` so a
    // peek-and-return to the info page doesn't pull the LaunchedEffect
    // out of composition and re-fire its IO on the way back. The flag
    // keeps the effect a one-shot even though it's now reachable on
    // every recomposition.
    var hasLoaded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (hasLoaded) return@LaunchedEffect
        loadEverything(context, rows, onTotals = { f, t ->
            freeBytes = f; totalBytes = t
        })
        mode = if (rows.isEmpty()) Mode.EMPTY else Mode.LIST
        selectedIdx = 0
        hasLoaded = true
    }

    if (showInfo) {
        StorageInfoScreen(
            freeBytes = freeBytes,
            addressableTotal = totalBytes,
            onBack = { showInfo = false },
        )
        return
    }

    LaunchedEffect(Unit) { focusRequester.requestFocus() }

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
                        onInfo = { showInfo = true },
                        onBack = onBack,
                    )
                    Mode.CONFIRM -> handleConfirmKey(
                        key = event.key,
                        onYes = {
                            val target = rows.getOrNull(pendingRowIdx)?.target
                            // Spotify is the special case: clearing files
                            // outside the app leaves spotify's offline-tracking
                            // DB intact, so it silently re-downloads
                            // everything next time it has wi-fi. The
                            // safe action is to drop the user into spotify
                            // and let them use the in-app
                            // settings → storage → remove all downloads.
                            // We don't run any IO here and don't enter
                            // CLEARING mode — the activity transition
                            // takes the user out of the launcher, and
                            // they'll come back via Back.
                            if (target == StorageCleanupOps.Target.SPOTIFY_OFFLINE) {
                                launchSpotify(context)
                                mode = Mode.LIST
                                return@handleConfirmKey
                            }
                            mode = Mode.CLEARING
                            // Kick off the clear on IO, then refresh.
                            // Tied to the composition's scope so leaving the
                            // screen mid-clear cancels cleanly.
                            coroutineScope.launch {
                                if (target == null) {
                                    mode = Mode.LIST
                                    return@launch
                                }
                                val result = withContext(Dispatchers.IO) {
                                    runCleanupFor(context, target)
                                }
                                // Confirmation via the system toast
                                // (the "default notification thing") —
                                // shorter to glance at than a sticky
                                // header line, and disappears on its
                                // own. Skip when bytesFreedDisplay is
                                // empty (e.g. an under-threshold
                                // no-op or a failed root call): the
                                // user already saw the deleting…
                                // panel, so a "cleared 0" toast would
                                // just be noise.
                                if (result.bytesFreedDisplay.isNotEmpty()) {
                                    Toast.makeText(
                                        context,
                                        "cleared ${result.bytesFreedDisplay}",
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                }
                                // Switch from CLEARING to LOADING so the
                                // "deleting…" UI doesn't linger during the
                                // size-refresh pass that follows. LOADING
                                // already renders the standard "checking
                                // storage…" CenterMessage + spinner, which
                                // matches what the user sees on initial
                                // open — same visual, same expectation.
                                mode = Mode.LOADING
                                rows.clear()
                                // Post-clear: same live-scan path as
                                // the initial open. Always reflects
                                // the just-cleared state.
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
            // Larger Helvetica title — overrides PageTitle's Cheltenham/22sp
            // because the storage screen wants more presence at the top.
            style = DumbTheme.Text.PageTitle.copy(
                color = DumbTheme.Colors.Yellow,
                fontFamily = DumbTheme.Body,
                fontSize = 34.sp,
                lineHeight = 40.sp,
            ),
            modifier = Modifier.padding(horizontal = DumbTheme.Spacing.ScreenPaddingH),
        )

        Spacer(Modifier.height(4.dp))

        BasicText(
            text = freeOfTotalLine(freeBytes, totalBytes),
            style = DumbTheme.Text.Subtitle,
            modifier = Modifier.padding(horizontal = DumbTheme.Spacing.ScreenPaddingH),
        )

        Spacer(Modifier.height(DumbTheme.Spacing.SectionGap))

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (mode) {
                Mode.LOADING -> CenterMessage(
                    text = "checking storage…",
                    showSpinner = true,
                )
                Mode.EMPTY -> CenterMessage(
                    "you're all set —\nnothing worth clearing right now",
                )
                Mode.LIST -> RowList(rows, selectedIdx)
                Mode.CONFIRM -> {
                    val row = rows.getOrNull(pendingRowIdx)
                    if (row != null) ConfirmPanel(row, clearing = false)
                    else CenterMessage("…")
                }
                // Stay on the same panel during the actual delete — the
                // user just pressed "yes, clear" and expects feedback
                // that something is happening, not a snap back to the
                // list with a partially-stale total. ConfirmPanel renders
                // its spinner + "deleting…" body while we await IO,
                // then the mode flips to LIST and brings the user back.
                Mode.CLEARING -> {
                    val row = rows.getOrNull(pendingRowIdx)
                    if (row != null) ConfirmPanel(row, clearing = true)
                    else CenterMessage("deleting…", showSpinner = true)
                }
            }
        }

        SoftKeyBar(
            leftLabel = when (mode) {
                // Per-row confirm label so the Spotify row reads
                // "yes, open" (we're launching Spotify, not deleting
                // anything ourselves). Falls back to "yes, clear" for
                // every other row that uses the default.
                Mode.CONFIRM -> rows.getOrNull(pendingRowIdx)?.confirmActionLabel
                    ?: "yes, clear"
                Mode.CLEARING -> "deleting…"
                // "info" lives on SoftLeft in list / empty states so
                // it's reachable even when there's nothing to clear.
                // The primary "clear" action moved to the OK / d-pad
                // center key — see centerLabel below.
                Mode.LIST, Mode.EMPTY -> "info"
                else -> null
            },
            centerLabel = when (mode) {
                // OK / d-pad center is the primary "clear" action on
                // the list. Suppressed in EMPTY (nothing to clear),
                // CONFIRM/CLEARING (already past the press), and
                // LOADING (no list yet).
                Mode.LIST -> "clear"
                else -> null
            },
            rightLabel = when (mode) {
                Mode.CONFIRM -> "cancel"
                Mode.CLEARING -> null
                else -> "back"
            },
        )
    }
}

// ── Sub-composables ──────────────────────────────────────────────────────

@Composable
private fun CenterMessage(text: String, showSpinner: Boolean = false) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = DumbTheme.Spacing.ScreenPaddingH),
        ) {
            if (showSpinner) {
                DumbSpinner()
                Spacer(Modifier.height(12.dp))
            }
            BasicText(
                text = text,
                style = DumbTheme.Text.Body.copy(
                    color = DumbTheme.Colors.Gray,
                    textAlign = TextAlign.Center,
                ),
            )
        }
    }
}

@Composable
private fun RowList(rows: List<Suggestion>, selectedIdx: Int) {
    // LazyColumn so the list scrolls when more rows are present than fit
    // on the screen — on a flip phone with the title + free/total header
    // above, that's typically 3–4 rows visible at a time.
    val listState = rememberLazyListState()

    // Auto-scroll the selected row into view as d-pad navigation moves
    // selection. We only animate when the selected index falls *outside*
    // the currently visible window — otherwise navigating between
    // already-visible rows would still re-center the list and look
    // jumpy. `viewportEndOffset - viewportStartOffset` gives the height
    // we actually have to lay items into.
    LaunchedEffect(selectedIdx, rows.size) {
        if (rows.isEmpty()) return@LaunchedEffect
        val visible = listState.layoutInfo.visibleItemsInfo
        if (visible.isEmpty()) {
            listState.scrollToItem(selectedIdx)
            return@LaunchedEffect
        }
        val viewportEnd = listState.layoutInfo.viewportEndOffset
        val viewportStart = listState.layoutInfo.viewportStartOffset
        val fullyVisible = visible.filter {
            it.offset >= viewportStart && it.offset + it.size <= viewportEnd
        }
        val firstFull = fullyVisible.firstOrNull()?.index ?: visible.first().index
        val lastFull = fullyVisible.lastOrNull()?.index ?: visible.last().index
        if (selectedIdx < firstFull || selectedIdx > lastFull) {
            listState.animateScrollToItem(selectedIdx)
        }
    }

    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        itemsIndexed(rows) { idx, row ->
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
private fun ConfirmPanel(row: Suggestion, clearing: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = DumbTheme.Spacing.ScreenPaddingH),
        verticalArrangement = Arrangement.Center,
    ) {
        // Title in helvetica (the launcher's body family) — matches the
        // "free up space" page title and reads cleaner at this size than
        // the default Title style. Stays yellow either way.
        BasicText(
            text = if (clearing) "clearing ${row.title.lowercase()}…"
            else "clear ${row.title.lowercase()}?",
            style = DumbTheme.Text.Title.copy(
                color = DumbTheme.Colors.Yellow,
                fontFamily = DumbTheme.Body,
            ),
        )
        Spacer(Modifier.height(12.dp))
        if (clearing) {
            // Active-operation feedback. The spinner + plain "deleting…"
            // text is the only thing on screen here so users aren't left
            // staring at the pre-confirm body wondering whether their
            // keypress registered.
            Row(verticalAlignment = Alignment.CenterVertically) {
                DumbSpinner()
                Spacer(Modifier.width(8.dp))
                BasicText(
                    text = "deleting…",
                    style = DumbTheme.Text.Body,
                )
            }
        } else {
            // Smaller body so the confirm body sits visually closer to a
            // hint than to the primary title — important on the flip
            // phone's narrow screen where the body easily wraps to
            // three+ lines.
            BasicText(
                text = row.confirmBody,
                style = DumbTheme.Text.Hint,
            )
            Spacer(Modifier.height(12.dp))
            BasicText(
                text = "frees ${humanBytes(row.sizeBytes)}",
                style = DumbTheme.Text.Subtitle,
            )
        }
    }
}

// ── Key handling ─────────────────────────────────────────────────────────

private fun handleListKey(
    key: Key,
    rows: List<Suggestion>,
    selectedIdx: Int,
    setSelectedIdx: (Int) -> Unit,
    onActivate: () -> Unit,
    onInfo: () -> Unit,
    onBack: () -> Unit,
): Boolean {
    if (rows.isEmpty()) {
        // Even with no clearable rows the user can still tap the labeled
        // "info" soft-key to read the info page — that's where the
        // support contact lives, which is the one thing they might
        // need when storage feels broken (e.g. nightly cleanup not
        // running, "everything's empty here but my phone is full").
        if (key == Key.SoftLeft) { onInfo(); return true }
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
        // OK / d-pad center is the primary "clear" action — it's the
        // most prominent key on the handset, and clearing is the
        // primary thing users come to this screen to do. SoftLeft is
        // labeled "info" and opens the info page (support contact +
        // explanation of nightly cleanup); the soft-key is the
        // secondary slot so it doesn't compete with the OK action.
        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> { onActivate(); true }
        Key.SoftLeft -> { onInfo(); true }
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
    // StatFs gives us the partition-level free figure (`/data` available
    // blocks). Cheap, no root, no thread switch.
    val data = Environment.getDataDirectory()
    val stat = StatFs(data.absolutePath)
    val free = stat.availableBlocksLong * stat.blockSizeLong

    // Every open runs a fresh scan. We used to read a nightly-cached
    // snapshot for instant render, but the 24h freshness window made
    // the screen wrong whenever the user had just downloaded something
    // (Spotify songs, photos, etc.) and wanted to free space NOW. The
    // user opens Free Up Space precisely *because* something changed,
    // so accuracy beats the ~2s "checking storage…" load cost.
    //
    // Per-section timing is logged inside `allSizesBytes` (see its
    // Log.i in StorageCleanupOps); if any single section creeps back
    // into the multi-second range we'll spot it without rerunning the
    // pieces in isolation.
    val sizesMap: MutableMap<StorageCleanupOps.Target, Long> =
        mutableMapOf()
    withContext(Dispatchers.IO) {
        val snapshot = StorageCleanupOps.allSizesBytes()
        // Default every known Target to 0 so missing-dir / no-root
        // cases don't leave a row with size=null down the line.
        for (t in StorageCleanupOps.Target.values()) {
            sizesMap[t] = snapshot.sizesByTarget[t] ?: 0L
        }
    }

    // Reframe "total" as the *addressable* pool — current free space plus
    // everything this screen can actually reclaim. The raw `/data`
    // partition size (4.5 GB on the TCL Flip 2) is dominated by APK
    // installs, OAT files, and dalvik-cache that the user can't clear
    // from this screen, so showing it as the denominator made the figure
    // misleading ("2.2 GB free of 4.6 GB" implied 2.4 GB of cleanable
    // headroom, when typically <200 MB is actually addressable here).
    //
    // Pool = free now + everything in `sizesMap`. If every bucket is
    // empty (root unavailable, or user has already cleared everything),
    // pool == free, and `freeOfTotalLine` collapses to "X free" with no
    // "of Y" tail — see the early-return in that helper.
    val addressableUsed = sizesMap.values.sum()
    val addressableTotal = free + addressableUsed
    onTotals(free, addressableTotal)

    // Build a row for every target that's over the threshold, then sort
    // by size descending so the biggest reclaimable buckets land at the
    // top of the list — that's where d-pad selection starts, so users
    // get to the most impactful clear with zero scrolling. Anything
    // under the threshold is dropped entirely (keeps the list focused).
    //
    // The `candidates` listing is also the tie-break order: when two
    // rows are exactly equal in size (vanishingly rare, but
    // deterministic matters for testing), the earlier candidate wins.
    // Recently-active categories — messaging attachments first, then
    // media, then catch-all — so a 0-byte tie still produces a sensible
    // ordering.
    val candidates = listOf(
        StorageCleanupOps.Target.WHATSAPP_MEDIA,
        StorageCleanupOps.Target.OPENBUBBLES_ATTACHMENTS,
        StorageCleanupOps.Target.SPOTIFY_OFFLINE,
        StorageCleanupOps.Target.ANTENNAPOD,
        StorageCleanupOps.Target.APPLE_MUSIC_OFFLINE,
    )
    val built = mutableListOf<Suggestion>()
    for (t in candidates) {
        val size = sizesMap[t] ?: 0L
        if (size < SIZE_HIDE_THRESHOLD_BYTES) continue
        built.add(rowFor(t, size))
    }
    built.sortByDescending { it.sizeBytes }
    rows.clear()
    rows.addAll(built)
}

private fun rowFor(
    target: StorageCleanupOps.Target,
    sizeBytes: Long,
): Suggestion = when (target) {
    StorageCleanupOps.Target.SPOTIFY_OFFLINE -> Suggestion(
        target = target,
        title = "spotify",
        description = "downloads & cache",
        sizeBytes = sizeBytes,
        // The wipe must happen inside spotify — clearing the files
        // externally leaves spotify's library DB intact, so it
        // re-downloads everything as soon as it has wi-fi.
        confirmBody = "tap yes to open spotify, then go to settings → " +
            "Remove all downloads & Clear cache.",
        confirmActionLabel = "yes, open",
    )
    StorageCleanupOps.Target.ANTENNAPOD -> Suggestion(
        target = target,
        title = "podcasts",
        description = "downloaded episodes",
        sizeBytes = sizeBytes,
        confirmBody = "your downloaded podcast episodes will be removed. they'll re-download next time you play them.",
    )
    StorageCleanupOps.Target.APPLE_MUSIC_OFFLINE -> Suggestion(
        target = target,
        title = "apple music offline",
        description = "downloaded songs",
        sizeBytes = sizeBytes,
        confirmBody = "your downloaded songs will be removed. you'll need wi-fi to download them again for offline listening.",
    )
    StorageCleanupOps.Target.WHATSAPP_MEDIA -> Suggestion(
        target = target,
        title = "whatsapp media",
        description = "photos & videos",
        sizeBytes = sizeBytes,
        // Mirrors the nightly cron: WHATSAPP_CRON_CUTOFF_DAYS = -1
        // tells WhatsAppOps.clearOldAttachments to drop the -mtime
        // predicate and remove every photo/video. Media stays on the
        // user's other devices and on WhatsApp's CDN (within
        // retention), so this is a local-only trim.
        confirmBody = "this will remove all whatsapp photos and videos from your dumb phone. they stay on your other devices.",
    )
    StorageCleanupOps.Target.OPENBUBBLES_ATTACHMENTS -> Suggestion(
        target = target,
        title = "smart txt attachments",
        description = "downloaded photos & files",
        sizeBytes = sizeBytes,
        confirmBody = "downloaded photos, videos, and files will be removed from this phone. your messages stay, and the originals stay on your other apple devices — open the message again on wi-fi and the attachment re-downloads.",
    )
}

private fun runCleanupFor(
    context: Context,
    target: StorageCleanupOps.Target,
): StorageCleanupOps.ClearResult = when (target) {
    StorageCleanupOps.Target.ANTENNAPOD -> StorageCleanupOps.clearAntennaPodEpisodes(context)
    // SPOTIFY_OFFLINE is normally short-circuited in the UI via
    // launchSpotify() before reaching runCleanupFor. This branch is
    // kept as a defensive fallback so accidentally routing Spotify
    // through here doesn't crash — though the redownload-on-resync
    // problem (see Suggestion.confirmActionLabel doc) still applies.
    StorageCleanupOps.Target.SPOTIFY_OFFLINE -> StorageCleanupOps.clearSpotifyOffline(context)
    StorageCleanupOps.Target.APPLE_MUSIC_OFFLINE -> StorageCleanupOps.clearAppleMusicOffline(context)
    StorageCleanupOps.Target.WHATSAPP_MEDIA -> StorageCleanupOps.clearWhatsAppOldMedia(context)
    StorageCleanupOps.Target.OPENBUBBLES_ATTACHMENTS -> StorageCleanupOps.clearOpenBubblesAttachments(context)
}

private const val SPOTIFY_PKG = "com.spotify.music"

/**
 * Launches Spotify and pops a toast telling the user where to go inside
 * the app. Falls back gracefully if Spotify isn't installed or the
 * launch intent isn't resolvable.
 *
 * Doesn't try a deep link to Spotify's settings — there's no documented
 * one as of 4.x, and trying an undocumented URI risks a `no activity
 * found` crash on a future Spotify update. The plain launch intent
 * drops the user into Spotify wherever they last were; the toast tells
 * them the three-step path to Settings → Storage → Remove all downloads.
 */
private fun launchSpotify(context: Context) {
    val intent = context.packageManager.getLaunchIntentForPackage(SPOTIFY_PKG)
    if (intent == null) {
        Log.w("FreeUpSpaceScreen", "launchSpotify: $SPOTIFY_PKG not installed")
        Toast.makeText(context, "spotify isn't installed", Toast.LENGTH_SHORT).show()
        return
    }
    // Length_LONG so the instruction is on screen long enough for the
    // user to start navigating Spotify's menu. About 3.5 seconds.
    Toast.makeText(
        context,
        "go to settings → Remove all downloads & Clear cache",
        Toast.LENGTH_LONG,
    ).show()
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Log.w("FreeUpSpaceScreen", "launchSpotify: ActivityNotFound — ${e.message}")
        Toast.makeText(context, "couldn't open spotify", Toast.LENGTH_SHORT).show()
    }
}

// ── Formatting helpers ───────────────────────────────────────────────────

// Package-scoped (no `private`) so `StorageInfoScreen` can share the same
// "X free of Y" rendering and the two pages stay visually consistent.
internal fun freeOfTotalLine(free: Long, total: Long): String {
    if (total <= 0) return ""
    // `total` here is the addressable pool (free + everything this screen
    // can reclaim). When the pool equals free, there's nothing for the
    // user to clean — drop the "of Y" tail rather than render the awkward
    // "2.2 GB free of 2.2 GB". humanBytes can also round both sides to
    // the same display string even if the byte counts differ by a few
    // hundred K, which would look like a typo, so collapse on display
    // equality too.
    val freeStr = humanBytes(free)
    val totalStr = humanBytes(total)
    if (total <= free || freeStr == totalStr) return "$freeStr free"
    return "$freeStr free of $totalStr"
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

