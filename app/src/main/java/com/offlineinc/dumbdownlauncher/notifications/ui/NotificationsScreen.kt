package com.offlineinc.dumbdownlauncher.notifications.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.offlineinc.dumbdownlauncher.notifications.model.NotificationItem
import com.offlineinc.dumbdownlauncher.ui.theme.DumbTheme

// Local aliases pointing at the central theme so the rest of this file
// can stay pithy. Update DumbTheme.Colors if you need to change these.
private val Yellow = DumbTheme.Colors.Yellow
private val Black = DumbTheme.Colors.Black
private val White = DumbTheme.Colors.White
private val Gray = DumbTheme.Colors.Gray

/**
 * Focus zones (top → bottom):
 *   1. DND toggle (always present at the top)
 *   2. Clear All button (only when notifications exist; autofocused on open)
 *   3. Notification list (only when notifications exist)
 *
 * When empty:
 *   1. DND toggle
 *   2. Empty state text
 */
@Composable
fun NotificationsScreen(
    items: List<NotificationItem>,
    onOpen: (NotificationItem) -> Unit,
    onDismiss: (NotificationItem) -> Unit,
    onClearAll: () -> Unit,
    scrollToKey: String? = null,
    onScrollConsumed: () -> Unit = {},
    messagesMuted: Boolean = false,
    onToggleMessagesMuted: ((Boolean) -> Unit)? = null,
) {
    val fontFamily = DumbTheme.BioRhyme
    val hasNotifications = items.isNotEmpty()
    val hasDndToggle = onToggleMessagesMuted != null

    // Selection state
    var selectedIndex by remember { mutableIntStateOf(0) }
    var selectionActive by remember { mutableStateOf(false) }

    // Focus requesters
    val clearAllFR = remember { FocusRequester() }
    val emptyFR = remember { FocusRequester() }
    val listFR = remember { FocusRequester() }
    val dndToggleFR = remember { FocusRequester() }

    // Focus tracking
    var clearAllFocused by remember { mutableStateOf(false) }
    var listFocused by remember { mutableStateOf(false) }
    var dndToggleFocused by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()

    // Clamp selection when list changes
    LaunchedEffect(items.size) {
        if (items.isEmpty()) {
            selectedIndex = 0
            selectionActive = false
        } else {
            selectedIndex = selectedIndex.coerceIn(0, items.lastIndex)
        }
    }

    // Scroll to a specific notification key
    LaunchedEffect(scrollToKey, items) {
        if (scrollToKey != null) {
            val idx = items.indexOfFirst { it.key == scrollToKey }
            if (idx >= 0) {
                selectedIndex = idx
                selectionActive = true
                listFR.requestFocus()
                listState.animateScrollToItem(idx)
                onScrollConsumed()
            }
        }
    }

    // Default focus behavior.
    // When notifications exist: autofocus Clear All (per design intent).
    // When empty: autofocus the DND toggle at the top (if present), otherwise empty state.
    LaunchedEffect(hasNotifications) {
        selectionActive = false
        if (hasNotifications) {
            clearAllFR.requestFocus()
        } else if (hasDndToggle) {
            dndToggleFR.requestFocus()
        } else {
            emptyFR.requestFocus()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Black)
            .padding(8.dp)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false

                // ── DND toggle focused (now at TOP) ──────────────────────
                if (dndToggleFocused) {
                    return@onPreviewKeyEvent when (event.key) {
                        Key.DirectionUp -> true // Already at top — consume, do nothing
                        Key.DirectionDown -> {
                            if (hasNotifications) {
                                // Move down to Clear All
                                clearAllFR.requestFocus()
                            } else {
                                emptyFR.requestFocus()
                            }
                            true
                        }
                        Key.Enter, Key.NumPadEnter, Key.DirectionCenter -> {
                            onToggleMessagesMuted?.invoke(!messagesMuted)
                            true
                        }
                        else -> false
                    }
                }

                // ── Empty state (no notifications, dnd not focused) ──────
                if (!hasNotifications && !dndToggleFocused) {
                    return@onPreviewKeyEvent when (event.key) {
                        Key.DirectionUp -> {
                            // Navigate up to DND toggle at top
                            if (hasDndToggle) dndToggleFR.requestFocus()
                            true
                        }
                        Key.DirectionDown,
                        Key.Enter,
                        Key.NumPadEnter,
                        Key.DirectionCenter -> true // Already at bottom — consume
                        else -> false
                    }
                }

                // ── Clear All focused ────────────────────────────────────
                if (clearAllFocused) {
                    return@onPreviewKeyEvent when (event.key) {
                        Key.DirectionUp -> {
                            // Navigate up to DND toggle at top
                            if (hasDndToggle) dndToggleFR.requestFocus()
                            true
                        }
                        Key.DirectionDown -> {
                            selectedIndex = 0
                            selectionActive = true
                            listFR.requestFocus()
                            true
                        }
                        Key.Enter, Key.NumPadEnter, Key.DirectionCenter -> {
                            onClearAll()
                            true
                        }
                        else -> false
                    }
                }

                // ── List focused ─────────────────────────────────────────
                if (listFocused) {
                    return@onPreviewKeyEvent when (event.key) {
                        Key.DirectionDown -> {
                            if (items.isNotEmpty() && selectedIndex < items.lastIndex) {
                                selectedIndex += 1
                            }
                            // DND toggle is now at the top — no downward exit from list
                            true
                        }
                        Key.DirectionUp -> {
                            if (selectedIndex == 0) {
                                selectionActive = false
                                clearAllFR.requestFocus()
                            } else {
                                selectedIndex = (selectedIndex - 1).coerceIn(0, items.lastIndex)
                            }
                            true
                        }
                        Key.Enter, Key.NumPadEnter, Key.DirectionCenter -> {
                            if (items.isNotEmpty()) onOpen(items[selectedIndex])
                            true
                        }
                        Key.Delete -> {
                            if (items.isNotEmpty()) onDismiss(items[selectedIndex])
                            true
                        }
                        else -> false
                    }
                }

                false
            }
    ) {
        // ── Header row: title (left) + mute toggle (right half) ─────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText(
                text = "notifications",
                style = TextStyle(
                    color = White,
                    fontSize = 16.sp,
                    fontFamily = fontFamily,
                ),
                modifier = Modifier.weight(1f),
            )
            if (hasDndToggle) {
                MuteToggleCell(
                    enabled = messagesMuted,
                    focused = dndToggleFocused,
                    fontFamily = fontFamily,
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(dndToggleFR)
                        .onFocusChanged { dndToggleFocused = it.isFocused }
                        .focusable(),
                    onClick = { onToggleMessagesMuted?.invoke(!messagesMuted) },
                )
            }
        }

        if (hasNotifications) {
            ClearAllButton(
                modifier = Modifier
                    .focusRequester(clearAllFR)
                    .onFocusChanged { state ->
                        clearAllFocused = state.isFocused
                        if (state.isFocused) selectionActive = false
                    },
                fontFamily = fontFamily,
                onClick = onClearAll
            )

            Spacer(Modifier.height(6.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .focusRequester(listFR)
                    .onFocusChanged { listFocused = it.isFocused }
                    .focusable()
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize()
                ) {
                    itemsIndexed(items, key = { _, it -> it.key }) { index, item ->
                        NotificationRow(
                            item = item,
                            selected = selectionActive && index == selectedIndex,
                            fontFamily = fontFamily,
                            onClick = { onOpen(item) }
                        )
                    }
                }
            }

            LaunchedEffect(selectedIndex) {
                if (items.isNotEmpty()) {
                    listState.animateScrollToItem(selectedIndex)
                }
            }
        } else {
            EmptyState(
                modifier = Modifier
                    .focusRequester(emptyFR)
                    .focusable(),
                fontFamily = fontFamily
            )
            Spacer(Modifier.weight(1f))
        }
    }
}

// ── Private composables ──────────────────────────────────────────────────────

/** Right-half cell of the header row — shows the mute toggle. */
@Composable
private fun MuteToggleCell(
    enabled: Boolean,
    focused: Boolean,
    fontFamily: FontFamily,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Row(
        modifier = modifier
            .background(if (focused) Yellow else Color.Transparent)
            .padding(horizontal = 6.dp, vertical = 4.dp)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onClick() },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.End,
    ) {
        BasicText(
            text = "mute txts",
            style = TextStyle(
                fontFamily = fontFamily,
                fontSize = 16.sp,
                color = if (focused) Black else White,
            ),
        )

        Spacer(Modifier.width(5.dp))

        // Toggle pill
        val trackColor = when {
            enabled && focused -> Black
            enabled -> Yellow
            else -> Gray
        }
        val thumbColor = if (focused) Yellow else White

        Box(
            modifier = Modifier
                .size(width = 28.dp, height = 16.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(trackColor),
            contentAlignment = if (enabled) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            Box(
                modifier = Modifier
                    .padding(2.dp)
                    .size(12.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(thumbColor)
            )
        }
    }
}

@Composable
private fun ClearAllButton(
    modifier: Modifier,
    fontFamily: FontFamily,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .wrapContentWidth()
            .background(if (focused) Yellow else Color.Transparent)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onClick() }
    ) {
        BasicText(
            text = "clear all",
            style = TextStyle(
                color = if (focused) Black else Yellow,
                fontSize = 16.sp,
                fontFamily = fontFamily
            )
        )
    }
}

@Composable
private fun EmptyState(
    modifier: Modifier,
    fontFamily: FontFamily
) {
    BasicText(
        text = "none... ur free!",
        style = TextStyle(
            color = Gray,
            fontSize = 16.sp,
            fontFamily = fontFamily
        ),
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp)
    )
}

@Composable
private fun NotificationRow(
    item: NotificationItem,
    selected: Boolean,
    fontFamily: FontFamily,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) Yellow else Color.Transparent)
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onClick() }
    ) {
        // Title: 20sp line height (down from 22) trims the padding below the
        // title while still leaving room for descenders at 16sp.
        BasicText(
            text = item.title,
            style = TextStyle(
                color = if (selected) Black else White,
                fontSize = 16.sp,
                lineHeight = 20.sp,
                fontFamily = fontFamily,
                platformStyle = PlatformTextStyle(includeFontPadding = true),
                lineHeightStyle = LineHeightStyle(
                    alignment = LineHeightStyle.Alignment.Center,
                    trim = LineHeightStyle.Trim.None,
                ),
            ),
            maxLines = 1
        )
        // Description (body): lineHeight reduced 20 → 16 so the two visible
        // lines sit closer together. The row-wide 2.dp spacer is gone —
        // Trim.None + the body's own lineHeight padding already provide
        // enough breathing room between title and body.
        BasicText(
            text = item.text,
            style = TextStyle(
                color = if (selected) Black else Gray,
                fontSize = 14.sp,
                lineHeight = 16.sp,
                fontFamily = fontFamily,
                platformStyle = PlatformTextStyle(includeFontPadding = true),
                lineHeightStyle = LineHeightStyle(
                    alignment = LineHeightStyle.Alignment.Center,
                    trim = LineHeightStyle.Trim.None,
                ),
            ),
            maxLines = 2
        )
    }
}
